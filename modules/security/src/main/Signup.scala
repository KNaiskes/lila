package lila.security

import play.api.data._
import play.api.i18n.Lang
import play.api.mvc.{ Request, RequestHeader }
import scala.util.chaining._

import lila.common.config.NetConfig
import lila.common.{ ApiVersion, EmailAddress, HTTPRequest }
import lila.user.{ PasswordHasher, User }

final class Signup(
    store: Store,
    api: SecurityApi,
    ipTrust: IpTrust,
    forms: DataForm,
    emailAddressValidator: EmailAddressValidator,
    emailConfirm: EmailConfirm,
    recaptcha: Recaptcha,
    authenticator: lila.user.Authenticator,
    userRepo: lila.user.UserRepo,
    slack: lila.slack.SlackApi,
    netConfig: NetConfig
)(implicit ec: scala.concurrent.ExecutionContext) {

  sealed abstract private class MustConfirmEmail(val value: Boolean)
  private object MustConfirmEmail {

    case object Nope                   extends MustConfirmEmail(false)
    case object YesBecausePrintExists  extends MustConfirmEmail(true)
    case object YesBecausePrintMissing extends MustConfirmEmail(true)
    case object YesBecauseIpExists     extends MustConfirmEmail(true)
    case object YesBecauseIpSusp       extends MustConfirmEmail(true)
    case object YesBecauseMobile       extends MustConfirmEmail(true)
    case object YesBecauseUA           extends MustConfirmEmail(true)

    def apply(print: Option[FingerPrint])(implicit req: RequestHeader): Fu[MustConfirmEmail] = {
      val ip = HTTPRequest lastRemoteAddress req
      store.recentByIpExists(ip) flatMap { ipExists =>
        if (ipExists) fuccess(YesBecauseIpExists)
        else if (HTTPRequest weirdUA req) fuccess(YesBecauseUA)
        else
          print.fold[Fu[MustConfirmEmail]](fuccess(YesBecausePrintMissing)) { fp =>
            store.recentByPrintExists(fp) flatMap { printFound =>
              if (printFound) fuccess(YesBecausePrintExists)
              else
                ipTrust.isSuspicious(ip).map {
                  case true => YesBecauseIpSusp
                  case _    => Nope
                }
            }
          }
      }
    }
  }

  def website(blind: Boolean)(implicit req: Request[_], lang: Lang): Fu[Signup.Result] =
    forms.signup.website.bindFromRequest.fold[Fu[Signup.Result]](
      err => fuccess(Signup.Bad(err tap signupErrLog)),
      data =>
        recaptcha.verify(~data.recaptchaResponse, req).flatMap {
          case false =>
            authLog(data.username, data.email, "Signup recaptcha fail")
            fuccess(Signup.Bad(forms.signup.website fill data))
          case true =>
            HasherRateLimit(data.username, req) {
              _ =>
                MustConfirmEmail(data.fingerPrint) flatMap {
                  mustConfirm =>
                    lila.mon.user.register.count(none)
                    lila.mon.user.register.mustConfirmEmail(mustConfirm.toString).increment()
                    val email = emailAddressValidator
                      .validate(data.realEmail) err s"Invalid email ${data.email}"
                    val passwordHash = authenticator passEnc User.ClearPassword(data.password)
                    userRepo
                      .create(
                        data.username,
                        passwordHash,
                        email.acceptable,
                        blind,
                        none,
                        mustConfirmEmail = mustConfirm.value
                      )
                      .orFail(s"No user could be created for ${data.username}")
                      .addEffect { logSignup(req, _, email.acceptable, data.fingerPrint, none, mustConfirm) }
                      .flatMap {
                        confirmOrAllSet(email, mustConfirm, data.fingerPrint, none)
                      }
                }
            }
        }
    )

  private def confirmOrAllSet(
      email: EmailAddressValidator.Acceptable,
      mustConfirm: MustConfirmEmail,
      fingerPrint: Option[FingerPrint],
      apiVersion: Option[ApiVersion]
  )(user: User)(implicit req: RequestHeader, lang: Lang): Fu[Signup.Result] =
    if (mustConfirm.value) {
      emailConfirm.send(user, email.acceptable) >> {
        if (emailConfirm.effective)
          api.saveSignup(user.id, apiVersion, fingerPrint) inject
            Signup.ConfirmEmail(user, email.acceptable)
        else fuccess(Signup.AllSet(user, email.acceptable))
      }
    } else fuccess(Signup.AllSet(user, email.acceptable))

  def mobile(
      apiVersion: ApiVersion
  )(implicit req: Request[_], lang: Lang): Fu[Signup.Result] =
    forms.signup.mobile.bindFromRequest.fold[Fu[Signup.Result]](
      err => fuccess(Signup.Bad(err tap signupErrLog)),
      data =>
        HasherRateLimit(data.username, req) { _ =>
          val email = emailAddressValidator
            .validate(data.realEmail) err s"Invalid email ${data.email}"
          val mustConfirm = MustConfirmEmail.YesBecauseMobile
          lila.mon.user.register.count(apiVersion.some)
          lila.mon.user.register.mustConfirmEmail(mustConfirm.toString).increment()
          val passwordHash = authenticator passEnc User.ClearPassword(data.password)
          userRepo
            .create(
              data.username,
              passwordHash,
              email.acceptable,
              false,
              apiVersion.some,
              mustConfirmEmail = mustConfirm.value
            )
            .orFail(s"No user could be created for ${data.username}")
            .addEffect { logSignup(req, _, email.acceptable, none, apiVersion.some, mustConfirm) }
            .flatMap {
              confirmOrAllSet(email, mustConfirm, none, apiVersion.some)
            }
        }
    )

  implicit private val ResultZero = ornicar.scalalib.Zero.instance[Signup.Result](Signup.RateLimited)

  private def HasherRateLimit =
    PasswordHasher.rateLimit[Signup.Result](enforce = netConfig.rateLimit) _

  private def logSignup(
      req: RequestHeader,
      user: User,
      email: EmailAddress,
      fingerPrint: Option[FingerPrint],
      apiVersion: Option[ApiVersion],
      mustConfirm: MustConfirmEmail
  ) = {
    authLog(
      user.username,
      email.value,
      s"fp: ${fingerPrint} mustConfirm: $mustConfirm fp: ${fingerPrint.??(_.value)} api: ${apiVersion.??(_.value)}"
    )
    val ip = HTTPRequest lastRemoteAddress req
    ipTrust.isSuspicious(ip) foreach { susp =>
      slack.signup(user, email, ip, fingerPrint.flatMap(_.hash).map(_.value), apiVersion, susp)
    }
  }

  private def signupErrLog(err: Form[_]) =
    for {
      username <- err("username").value
      email    <- err("email").value
    } {
      if (err.errors.exists(_.messages.contains("error.email_acceptable")) &&
          err("email").value.exists(EmailAddress.matches))
        authLog(username, email, s"Signup with unacceptable email")
    }

  private def authLog(user: String, email: String, msg: String) =
    lila.log("auth").info(s"$user $email $msg")
}

object Signup {

  sealed trait Result
  case class Bad(err: Form[_])                             extends Result
  case object RateLimited                                  extends Result
  case class ConfirmEmail(user: User, email: EmailAddress) extends Result
  case class AllSet(user: User, email: EmailAddress)       extends Result
}
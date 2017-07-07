/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service

import com.softwaremill.macwire._
import com.waz.HockeyApp
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.ClientRegistrationState
import com.waz.api.impl._
import com.waz.content.Preferences.Preference
import com.waz.model.{UserData, _}
import com.waz.model.otr.Client
import com.waz.service.otr.{OtrClientsService, VerificationStateUpdater}
import com.waz.sync._
import com.waz.sync.client.OtrClient
import com.waz.sync.otr.OtrClientsSyncHandler
import com.waz.threading.{CancellableFuture, SerialDispatchQueue, Threading}
import com.waz.utils._
import com.waz.utils.events.{EventContext, Signal}
import com.waz.znet.AuthenticationManager._
import com.waz.znet.CredentialsHandler
import com.waz.znet.Response.Status
import com.waz.znet.ZNetClient._
import org.threeten.bp.Instant
import com.waz.utils.RichInstant

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Right

class UserModule(val userId: UserId, val account: AccountManager) {
  import Threading.Implicits.Background

  def context = account.global.context
  def db = account.storage.db
  def clientId = account.clientId
  def accountId = account.id
  def timeouts = account.global.timeouts
  def network = account.global.network
  def userPrefs = account.storage.userPrefs
  def usersStorage = account.storage.usersStorage
  def convsStorage = account.storage.convsStorage
  def membersStorage = account.storage.membersStorage
  def clientsStorage = account.storage.otrClientsStorage
  def lifecycle = account.global.lifecycle
  def cryptoBox = account.cryptoBox
  def reporting = account.global.reporting

  lazy val otrClient = new OtrClient(account.netClient)

  lazy val verificationUpdater                = wire[VerificationStateUpdater]
  lazy val clientsService: OtrClientsService  = wire[OtrClientsService]
  lazy val clientsSync: OtrClientsSyncHandler = wire[OtrClientsSyncHandler]

  lazy val sync: SyncServiceHandle            = wire[AndroidSyncServiceHandle]
  lazy val syncRequests: SyncRequestService   = wire[SyncRequestService]
  lazy val syncHandler: SyncHandler           = new AccountSyncHandler(account.zmessaging.collect { case Some(zms) => zms }, clientsSync)

  def ensureClientRegistered(account: AccountData): ErrorOr[AccountData] =
    if (account.clientId.isDefined) Future successful Right(account)
    else {
      import com.waz.api.ClientRegistrationState._
      clientsSync.registerClient(account.password) map {
        case Right((REGISTERED, Some(cl))) =>
          Right(account.copy(clientId = Some(cl.id), clientRegState = REGISTERED, verified = true))
        case Right((state, _)) =>
          sync.syncSelfClients() // request clients sync, UI will need that
          Right(account.copy(clientRegState = state))
        case Left(err) =>
          error(s"client registration failed: $err")
          Left(err)
      }
    }
}

class AccountManager(val id: AccountId, val global: GlobalModule, accounts: AccountsService)(implicit ec: EventContext) { self =>
  import AccountManager._
  private implicit val dispatcher = new SerialDispatchQueue()
  verbose(s"Creating for: $id")

  import global._
  val storage: StorageModule = global.factory.baseStorage(id)

  accountData.onChanged { acc =>
    acc.userId.foreach { uId =>
      storage.usersStorage.updateOrCreate(uId, identity, UserData(uId, None, "", acc.email, acc.phone, searchKey = SearchKey(""), connection = UserData.ConnectionStatus.Self, handle = acc.handle))
    }
  }

  // listen to client changes, logout and delete cryptobox if current client is removed
  private val otrClient = accountData.map(a => (a.userId, a.clientId)).flatMap {
    case (Some(uId), Some(cId)) => storage.otrClientsStorage.optSignal(uId).map(_.flatMap(_.clients.get(cId)))
    case _                         => Signal const Option.empty[Client]
  }

  private var hasClient = false
  otrClient.map(_.isDefined) { exists =>
    if (hasClient && !exists) {
      info(s"client has been removed on backend, logging out")
      logoutAndResetClient()
    }
    hasClient = exists
  }

  private val selfUserData = accountData.map(_.userId).flatMap {
    case Some(uId) => storage.usersStorage.optSignal(uId)
    case None         => Signal const Option.empty[UserData]
  }

  // listen to user data changes, update account email/phone if self user data is changed
  selfUserData.collect {
    case Some(user) => (user.email, user.phone)
  } { case (email, phone) =>
    verbose(s"self user data changed, email: $email, phone: $phone")
    accountsStorage.update(id, _.copy(email = email, phone = phone))
  }

  selfUserData.map(_.exists(_.deleted)) { deleted =>
    if (deleted) {
      info(s"self user was deleted, logging out")
      for {
        _ <- logoutAndResetClient()
        _ =  accounts.accountMap.remove(id)
        _ <- accountsStorage.remove(id)
      // TODO: delete database, account was deleted
      } yield ()
    }
  }

  lazy val cryptoBox          = global.factory.cryptobox(id, storage)
  lazy val netClient          = global.factory.client(credentialsHandler)
  lazy val usersClient        = global.factory.usersClient(netClient)
  lazy val teamsClient        = global.factory.teamsClient(netClient)
  lazy val credentialsClient  = global.factory.credentialsClient(netClient)

  @volatile private var _userModule = Option.empty[UserModule]

  lazy val userModule = userId collect {
    case Some(user) =>
      _userModule = Some(_userModule.getOrElse(global.factory.userModule(user, this)))
      _userModule.get
  }

  @volatile
  private[waz] var credentials = Credentials.Empty

  lazy val credentialsHandler = new CredentialsHandler {
    override val userId: AccountId = id
    override val cookie: Preference[Option[Cookie]] = Preference[Option[Cookie]](None, accountsStorage.get(id).map(_.flatMap(_.cookie)), { c: Option[Cookie] => accountsStorage.update(id, _.copy(cookie = c)) })
    override val accessToken: Preference[Option[Token]] = Preference[Option[Token]](None, accountsStorage.get(id).map(_.flatMap(_.accessToken)), { token: Option[Token] => accountsStorage.update(id, _.copy(accessToken = token)) })
    override def credentials: Credentials = self.credentials

    override def onInvalidCredentials(): Unit = logout(flushCredentials = true)
  }

  lazy val accountData = accountsStorage.signal(id)

  lazy val clientId = accountData.map(_.clientId)

  lazy val userId = accountData.map(_.userId)

  // logged in zmessaging instance
  @volatile private var _zmessaging = Option.empty[ZMessaging]

  val zmessaging = (for {
    Some(cId)  <- clientId
    Right(tId) <- accountData.flatMap(a => Signal.future(loadSelfTeam(a)).map(_ => a.teamId))
    um         <- userModule
    Some(_)    <- Signal.future { cryptoBox.cryptoBox }
  } yield {
    verbose(s"Creating new ZMessaging instance, for $um, $cId, $tId, service: $this")
    _zmessaging = _zmessaging orElse LoggedTry(global.factory.zmessaging(tId, cId, um)).toOption
    _zmessaging
  }).orElse(Signal const Option.empty[ZMessaging])

  val isLoggedIn = accounts.activeAccountPref.signal.map(_.contains(id))
  isLoggedIn.onUi { lifecycle.setLoggedIn }

  accountData { acc =>
    if (acc.cookie.isDefined) {
      if (credentials == Credentials.Empty) credentials = acc.credentials
    }
  } (EventContext.Global)

  for {
    acc          <- accountData
    loggedIn     <- isLoggedIn
    Some(client) <- otrClient
  } {
    if (loggedIn && acc.verified) {
      if (acc.userId.isEmpty || acc.clientId.isEmpty) {
        verbose(s"account data needs registration: $acc")
        Serialized.future(self)(ensureFullyRegistered())
      }

      if (client.signalingKey.isEmpty) {
        returning (s"Client registered ${client.regTime.map(_ until Instant.now).map(_.toDays).getOrElse(0)} ago is missing its signaling key") { msg =>
          warn(msg)
          HockeyApp.saveException(new IllegalStateException(msg), msg)
        }
        Serialized.future(self)(userModule.head.map(_.clientsSync.registerSignalingKey()))
      }
    }
  }

  private var awaitActivationFuture = CancellableFuture successful Option.empty[AccountData]

  private val shouldAwaitActivation = lifecycle.uiActive.zip(accountsStorage.optSignal(id)) map {
    case (true, Some(acc)) => !acc.verified && acc.password.isDefined
    case _ => false
  }

  shouldAwaitActivation.on(dispatcher) {
    case true   => awaitActivationFuture = awaitActivationFuture.recover { case _: Throwable => () } flatMap { _ => awaitActivation(0) }
    case false  => awaitActivationFuture.cancel()("stop_await_activate")
  }

  lifecycle.lifecycleState { state => verbose(s"lifecycle state: $state") }

  def login(credentials: Credentials): Future[Either[ErrorResponse, AccountData]] =
    Serialized.future(this) {
      verbose(s"login($credentials)")
      self.credentials = credentials
      accountsStorage.updateOrCreate(id, _.updated(credentials), AccountData().updated(credentials)) flatMap { _ => ensureFullyRegistered() }
    }

  def logout(flushCredentials: Boolean): Future[Unit] = {
    verbose(s"logout($id)")
    for {
      _ <- if (flushCredentials) Future(credentials = Credentials.Empty) else Future.successful({})
      _ <- accounts.logout(id, flushCredentials)
    } yield {}
  }

  def getZMessaging: Future[Option[ZMessaging]] = zmessaging.head flatMap {
    case Some(zms) => Future successful Some(zms)
    case None =>
      Serialized.future(this) {
        accountsStorage.get(id) flatMap {
          case Some(ad) if ad.cookie.isEmpty =>
            verbose(s"account data has no cookie, user not logged in: $ad")
            Future successful None
          case Some(acc) =>
            ensureFullyRegistered() flatMap {
              case Right(a) if a.userId.isDefined && a.clientId.isDefined && a.verified =>
                zmessaging.filter(_.isDefined).head // wait until loaded
              case _ =>
                zmessaging.head
            }
          case None =>
            Future successful None
        }
      }
  }

  def updateEmail(email: EmailAddress): ErrorOrResponse[Unit] = credentialsClient.updateEmail(email)

  def clearEmail(): ErrorOr[Unit] =
    credentialsClient.clearEmail().future.flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_)  => updateSelfAccountAndUser(_.copy(email = None), _.copy(email = None)).map(_ => Right({}))

    }

  def updatePhone(phone: PhoneNumber): ErrorOrResponse[Unit] = credentialsClient.updatePhone(phone)

  def clearPhone(): ErrorOr[Unit] =
    credentialsClient.clearPhone().future.flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_)  => updateSelfAccountAndUser(_.copy(phone = None), _.copy(phone = None)).map(_ => Right({}))
    }

  def updatePassword(newPassword: String, currentPassword: Option[String]) =
    credentialsClient.updatePassword(newPassword, currentPassword).future flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_) =>
        accountsStorage.update(id, _.copy(hash = AccountData.computeHash(id, newPassword), password = Some(newPassword))) flatMap { _ =>
          getZMessaging map { _ => Right(()) }
        }
    }

  def updateHandle(handle: Handle): ErrorOr[Unit] =
    credentialsClient.updateHandle(handle).future.flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_)  => updateSelfAccountAndUser(_.copy(handle = Some(handle)), _.copy(handle = Some(handle))).map(_ => Right({}))
    }

  private def updateSelfAccountAndUser(acc: AccountData => AccountData, user: UserData => UserData) = {
    for {
      _         <- accountsStorage.update(id, acc)
      Some(zms) <- getZMessaging
      _         <- zms.usersStorage.update(zms.selfUserId, user)
    } yield {}
  }

  private def loadSelfTeam(account: AccountData): Future[Either[ErrorResponse, AccountData]] =
    account.teamId match {
      case Right(_) =>
        Future successful Right(account)
      case Left(_) =>
        teamsClient.findSelfTeam().future flatMap {
          case Right(teamOpt) =>
            verbose(s"got self team: $teamOpt")

            val permissions = (teamOpt, account.userId) match {
              case (Some(t), Some(u)) =>
                teamsClient.getPermissions(t.id, u).map {
                  case Right(p) => Some(p)
                  case Left(_)  => None
                }.future
              case _ => Future.successful(None)
            }

            for {
              _ <- (teamOpt, account.userId) match {
                case (Some(t), Some(uId)) =>
                  storage.usersStorage.update(uId, _.updated(Some(t.id))).map(_ => {})
                case _ => Future.successful({})
              }
              _   <- teamOpt match {
                case Some(t) => teamsStorage.updateOrCreate(t.id, _ => t, t)
                case _       => Future.successful({})
              }
              p   <- permissions
              res <- accountsStorage.updateOrCreate(id, _.withTeam(teamOpt.map(_.id), p), account.withTeam(teamOpt.map(_.id), p))
            } yield Right(res)

          case Left(err) => Future successful Left(err)
        }
    }

  private[service] def ensureFullyRegistered(): Future[Either[ErrorResponse, AccountData]] = {
    verbose(s"ensureFullyRegistered()")

    def loadSelfUser(account: AccountData): Future[Either[ErrorResponse, AccountData]] =
      if (account.userId.isDefined) Future successful Right(account)
      else {
        usersClient.loadSelf().future flatMap {
          case Right(userInfo) =>
            verbose(s"got self user info: $userInfo")
            for {
              _ <- storage.assetsStorage.mergeOrCreateAsset(userInfo.mediumPicture)
              _ <- storage.usersStorage.updateOrCreate(userInfo.id, _.updated(userInfo).copy(syncTimestamp = System.currentTimeMillis()), UserData(userInfo).copy(connection = UserData.ConnectionStatus.Self, syncTimestamp = System.currentTimeMillis()))
              res <- accountsStorage.updateOrCreate(id, _.updated(userInfo), account.updated(userInfo))
            } yield Right(res)
          case Left(err) =>
            verbose(s"loadSelfUser failed: $err")
            Future successful Left(err)
        }
      }

    def checkCryptoBox =
      cryptoBox.cryptoBox flatMap {
        case Some(cb) => Future successful Some(cb)
        case None =>
          _zmessaging = None
          for {
            _ <- accountsStorage.update(id, _.copy(clientId = None, clientRegState = ClientRegistrationState.UNKNOWN))
            _ <- cryptoBox.deleteCryptoBox()
            res <- cryptoBox.cryptoBox
          } yield res
      }

    // TODO: (Maciek) I'm pretty sure we can flatten this to something like f1.flatMap(res1 => f2).flatMap(res2 => f3) ...
    checkCryptoBox flatMap {
      case None => Future successful Left(ErrorResponse.internalError("CryptoBox loading failed"))
      case Some(_) =>
        accountsStorage.get(id) flatMap {
          case None => Future successful Left(ErrorResponse.internalError(s"Missing AccountData for id: $id"))
          case Some(acc) =>
            activate(acc, credentials).future flatMap {
              case Right(acc1) if acc1.verified =>
                loadSelfUser(acc1) flatMap {
                  case Right(acc2) =>
                    // TODO: check if there is some other AccountData with the same userId already present
                    // it may happen that the same account was previously used with different credentials
                    // we should merge those accounts, delete current one, and switch to the previous one
                    userModule.head flatMap { _.ensureClientRegistered(acc2) } flatMap {
                      case Right(acc3) =>
                        loadSelfTeam(acc3) flatMap {
                          case Right(acc4) =>
                            accountsStorage.updateOrCreate(id, _.updated(acc4.userId, acc4.verified, acc4.clientId, acc4.clientRegState), acc4) map { Right(_) }
                          case Left(err) => Future successful Left(err)
                        }
                      case Left(err) => Future successful Left(err)
                    }
                  case Left(err) => Future successful Left(err)
                }
              case Right(acc1) => Future successful Right(acc1)
              case Left(err) => Future successful Left(err)
          }
        }
    }
  }

  private def activate(account: AccountData, credentials: Credentials): CancellableFuture[Either[ErrorResponse, AccountData]] =
    if (account.verified) CancellableFuture successful Right(account)
    else loginClient.login(account.id, credentials) flatMap {
      case Right((token, cookie)) =>
        CancellableFuture lift {
          for {
            _ <- credentialsHandler.cookie := cookie
            _ <- credentialsHandler.accessToken := Some(token)
            acc <- accountsStorage.updateOrCreate(id, _.copy(verified = true, cookie = cookie, accessToken = Some(token)), account.copy(verified = true, cookie = cookie, accessToken = Some(token)))
          } yield Right(acc)
        }
      case Left((_, ErrorResponse(Status.Forbidden, _, "pending-activation"))) =>
        CancellableFuture successful Right(account.copy(verified = false))
      case Left((_, err)) =>
        verbose(s"activate failed: $err")
        CancellableFuture successful Left(err)
    }

  private def awaitActivation(retry: Int = 0): CancellableFuture[Option[AccountData]] =
    CancellableFuture lift accountsStorage.get(id) flatMap {
      case None => CancellableFuture successful None
      case Some(data) if data.verified => CancellableFuture successful Some(data)
      case Some(_) if !lifecycle.isUiActive => CancellableFuture successful None
      case Some(data) =>
        activate(data, credentials) flatMap {
          case Right(acc) if acc.verified => CancellableFuture successful Some(acc)
          case _ =>
            CancellableFuture.delay(ActivationThrottling.delay(retry)) flatMap { _ => awaitActivation(retry + 1) }
        }
    }

  private def logoutAndResetClient() =
    for {
      _ <- logout(true)
      _ <- cryptoBox.deleteCryptoBox()
      _ =  _zmessaging = None // drop zmessaging instance, we need to create fresh one with new clientId // FIXME: dropped instance will still be active and using the same ZmsLifecycle instance
      _ <- accountsStorage.update(id, _.copy(clientId = None, clientRegState = ClientRegistrationState.UNKNOWN))
    } yield ()
}

object AccountManager {
  val ActivationThrottling = new ExponentialBackoff(2.seconds, 15.seconds)
}

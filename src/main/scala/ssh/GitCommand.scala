package ssh

import org.apache.sshd.server.{CommandFactory, Environment, ExitCallback, Command}
import org.slf4j.LoggerFactory
import java.io.{InputStream, OutputStream}
import util.ControlUtil._
import org.eclipse.jgit.api.Git
import util.Directory._
import org.eclipse.jgit.transport.{ReceivePack, UploadPack}
import org.apache.sshd.server.command.UnknownCommand
import servlet.{Database, CommitLogHook}
import service.SystemSettingsService
import org.eclipse.jgit.errors.RepositoryNotFoundException


class GitCommandFactory extends CommandFactory {
  private val logger = LoggerFactory.getLogger(classOf[GitCommandFactory])

  override def createCommand(command: String): Command = {
    logger.debug(s"command: $command")
    command match {
      // TODO MUST use regular expression and UnitTest
      case s if s.startsWith("git-upload-pack") => new GitUploadPack(command)
      case s if s.startsWith("git-receive-pack") => new GitReceivePack(command)
      case _ => new UnknownCommand(command)
    }
  }
}

abstract class GitCommand(val command: String) extends Command {
  protected val logger = LoggerFactory.getLogger(classOf[GitCommand])
  protected val (gitCommand, owner, repositoryName) = parseCommand
  protected var err: OutputStream = null
  protected var in: InputStream = null
  protected var out: OutputStream = null
  protected var callback: ExitCallback = null

  protected def runnable(user: String): Runnable

  override def start(env: Environment): Unit = {
    logger.info(s"start command : " + command)
    logger.info(s"parsed command : $gitCommand, $owner, $repositoryName")
    val user = env.getEnv.get("USER")
    val thread = new Thread(runnable(user))
    thread.start()
  }

  override def destroy(): Unit = {}

  override def setExitCallback(callback: ExitCallback): Unit = {
    this.callback = callback
  }

  override def setErrorStream(err: OutputStream): Unit = {
    this.err = err
  }

  override def setOutputStream(out: OutputStream): Unit = {
    this.out = out
  }

  override def setInputStream(in: InputStream): Unit = {
    this.in = in
  }

  private def parseCommand: (String, String, String) = {
    // command sample: git-upload-pack '/owner/repository_name.git'
    // command sample: git-receive-pack '/owner/repository_name.git'
    // TODO This is not correct.... but works
    val split = command.split(" ")
    val gitCommand = split(0)
    val owner = split(1).substring(1, split(1).length - 5).split("/")(1)
    val repositoryName = split(1).substring(1, split(1).length - 5).split("/")(2)
    (gitCommand, owner, repositoryName)
  }
}

class GitUploadPack(override val command: String) extends GitCommand(command: String) {
  override def runnable(user: String) = new Runnable {
    override def run(): Unit = {
      try {
        using(Git.open(getRepositoryDir(owner, repositoryName))) {
          git =>
            val repository = git.getRepository
            val upload = new UploadPack(repository)
            try {
              upload.upload(in, out, err)
              callback.onExit(0)
            } catch {
              case e: Throwable =>
                logger.error(e.getMessage, e)
                callback.onExit(1)
            }
        }
      } catch {
        case e: RepositoryNotFoundException =>
          logger.info(e.getMessage, e)
          callback.onExit(1)
      }
    }
  }
}

class GitReceivePack(override val command: String) extends GitCommand(command: String) with SystemSettingsService {
  // TODO Correct this info. where i get base url?
  val BaseURL: String = loadSystemSettings().baseUrl.getOrElse("http://localhost:8080")

  override def runnable(user: String) = new Runnable {
    override def run(): Unit = {
      try {
        using(Git.open(getRepositoryDir(owner, repositoryName))) {
          git =>
            val repository = git.getRepository
            val receive = new ReceivePack(repository)
            receive.setPostReceiveHook(new CommitLogHook(owner, repositoryName, user, BaseURL))
            Database(SshServer.getServletContext) withTransaction {
              try {
                receive.receive(in, out, err)
                callback.onExit(0)
              } catch {
                case e: Throwable =>
                  logger.error(e.getMessage, e)
                  callback.onExit(1)
              }
            }
        }
      } catch {
        case e: RepositoryNotFoundException =>
          logger.info(e.getMessage, e)
          callback.onExit(1)
      }
    }
  }

}
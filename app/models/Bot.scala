package models

import akka.actor._
import akka.util.Timeout
import akka.pattern.ask

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.libs.Akka

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import java.io.FileInputStream

import opennlp.tools.tokenize._
import opennlp.tools.sentdetect._

object Bot {
  final val RESPONSE_PROBABILITY = 0.05
  final val SPAWN_PROBABILITY = 0.9999

  private val corpus = scala.io.Source.fromFile("project/fodder.txt", "utf-8").getLines.mkString
  private val usernames = prepareUsernames(corpus)
  private val fodder = prepareFodder(corpus)

  // Create a bot for a mob.
  def apply(mob: ActorRef) {
    // Select a random token as a username.
    val username = usernames(Random.nextInt(usernames.length))

    try {
      Akka.system.actorOf(Props(new Bot(username, mob)), username)
    } catch {
      case e: InvalidActorNameException => // do nothing; just don't make the bot.
    }
  }

  def populateMob(mob: ActorRef) {
    // Add new Bot to the mob.
    apply(mob)

    if (Random.nextFloat < SPAWN_PROBABILITY) {
      populateMob(mob)
    }
  }

  private def prepareFodder(corpus: String) = {
    val sentenceModelInput = new FileInputStream("project/en-sent.bin")
    val sentenceModel = new SentenceModel(sentenceModelInput)
    val sentenceDetector = new SentenceDetectorME(sentenceModel)
    sentenceModelInput.close()

    sentenceDetector.sentDetect(corpus)
  }

  private def prepareUsernames(corpus: String) = {
    val simpleTokenizer = SimpleTokenizer.INSTANCE
    simpleTokenizer.tokenize(corpus)
  }

  def generateSpeech = {
      fodder(Random.nextInt(fodder.length))
  }
}

class Bot(username: String, mob: ActorRef) extends Actor {
  implicit val timeout = Timeout(1 second)
  val personality = Personality.apply

  // This is the consumer of the chat channel enumerator.
  val chatIteratee = Iteratee.foreach[JsValue] { event =>
    event \ "kind" match {
      case JsString("talk") => {
        val message = (event \ "message").as[String]
        val user = (event \ "user").as[String]
        self ! RespondTo(user, message)
      }
      case _ => // do nothing
    }
  }

  // Join the mob.
  mob ? (Join(username)) map {
    case Connected(botChannel) => {
      // Apply this iteratee on the socket enumerator.
      //Logger.info(this + " has successfully joined.")
      botChannel |>> chatIteratee

      mob ! RegisterBot(username)
    }
    case CannotConnect(error) => {
      self ! PoisonPill
    }
  }

  // The bot leaves and terminates after
  // its lifetime has expired.
  val deathclock = context.system.scheduler.scheduleOnce(personality.lifetime) {
    self ! PoisonPill
  }

  // Schedule the bot's talking.
  if (personality.nextTalk > Duration.Zero) {
    beginTalkPattern
  }

  def receive = {
    // Respond to a specific user.
    case RespondTo(fromUser, message) => {

      // First check if the user was mentioned.
      if ((message contains " " + username + " ") && (Random.nextFloat < Bot.RESPONSE_PROBABILITY)) {
        mob ! Talk(username, fromUser + ": im talkin to you")
      }
    }
  }

  override def postStop = {
    deathclock.cancel
    mob ! DeregisterBot(username)
  }

  def beginTalkPattern: Unit = {
    context.system.scheduler.scheduleOnce(personality.nextTalk) {
      try {
        val speech = Bot.generateSpeech
        mob ! Talk(username, speech)
        beginTalkPattern
      } catch {
        case e: NullPointerException => // happens when this is executed after the bot is terminated 
      }
    }
  }

  override def toString = {
    username + " (" + personality + ")"
  }
}


object Personality {
  def apply =
    Random.nextFloat match {
      case r if r < 0.75 => new Lurker
      case r if r < 0.95 => new Regular
      case _ => new Chatty
    }
}

abstract class Personality {
  // Lifetime of the bot in seconds.
  // i.e. how long before it leaves.
  // Default: 30-60min
  val lifetime: FiniteDuration = (30 + Random.nextFloat * 30).minutes

  // When the bot next speaks.
  def nextTalk: FiniteDuration
}
class Chatty extends Personality {
  // 0.5-11sec
  def nextTalk: FiniteDuration = (0.5 + Random.nextFloat * 10).seconds
}
class Regular extends Personality {
  // 5-25min
  def nextTalk: FiniteDuration = (5 + Random.nextFloat * 20).minutes
}
class Lurker extends Personality {
  // Lurkers never speak.
  def nextTalk: FiniteDuration = Duration.Zero
}

case class RespondTo(username: String, message: String)
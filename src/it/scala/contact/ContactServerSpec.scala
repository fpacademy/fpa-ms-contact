package contact

import cats.implicits._
import cats.effect.IO

import io.circe._
import io.circe.literal._
import io.circe.optics.JsonPath._

import org.http4s._
import org.http4s.circe._
import org.http4s.client.blaze._
import org.http4s.server.blaze._
import org.http4s.server.{Server => Http4sServer}
import org.http4s.server.middleware.Logger

import org.scalatest._

import fpa._

class ContactServerSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  private lazy val client = Http1Client[IO]().unsafeRunSync()

  private lazy val config = Config.load[IO]("test.conf").unsafeRunSync()

  private lazy val urlStart = s"http://${config.server.host}:${config.server.port}"

  private val server = createServer().unsafeRunSync()

  override def afterAll(): Unit = {
    client.shutdown.unsafeRunSync()
    server.shutdown.unsafeRunSync()
  }

  "Contact server" should {
    "create a Contact" in {
      val description = "create contact"
      val importance = "high"
      val createJson =json"""
        {
          "description": $description,
          "importance": $importance
        }"""
      val request = Request[IO](method = Method.POST, uri = Uri.unsafeFromString(s"$urlStart/contacts")).withBody(createJson).unsafeRunSync()
      val json = client.expect[Json](request).unsafeRunSync()
      root.id.string.getOption(json).nonEmpty shouldBe true
      root.description.string.getOption(json) shouldBe Some(description)
      root.importance.string.getOption(json) shouldBe Some(importance)
    }

    "update a Contact" in {
      val id = createContact("update contact (before)", "low")

      val description = "update contact (after)"
      val importance = "medium"
      val updateJson = json"""
        {
          "description": $description,
          "importance": $importance
        }"""
      val request = Request[IO](method = Method.PUT, uri = Uri.unsafeFromString(s"$urlStart/contacts/$id")).withBody(updateJson).unsafeRunSync()
      client.expect[Json](request).unsafeRunSync() shouldBe json"""
        {
          "id": $id,
          "description": $description,
          "importance": $importance
        }"""
    }

    "return a single Contact" in {
      val description = "read contact"
      val importance = "medium"
      val id = createContact(description, importance)
      client.expect[Json](Uri.unsafeFromString(s"$urlStart/contacts/$id")).unsafeRunSync() shouldBe json"""
        {
          "id": $id,
          "description": $description,
          "importance": $importance
        }"""
    }

    "delete a Contact" in {
      val description = "delete contact"
      val importance = "low"
      val id = createContact(description, importance)
      val deleteRequest = Request[IO](method = Method.DELETE, uri = Uri.unsafeFromString(s"$urlStart/contacts/$id"))
      client.status(deleteRequest).unsafeRunSync() shouldBe Status.NoContent

      val getRequest = Request[IO](method = Method.GET, uri = Uri.unsafeFromString(s"$urlStart/contacts/$id"))
      client.status(getRequest).unsafeRunSync() shouldBe Status.NotFound
    }

    "return all Contacts" in {
      // Remove all existing Contacts
      val contacts = client.expect[Json](Uri.unsafeFromString(s"$urlStart/contacts")).unsafeRunSync()
      root.each.id.string.getAll(contacts).map(id => {
        val req = Request[IO](method = Method.DELETE, uri = Uri.unsafeFromString(s"$urlStart/contacts/$id"))
        client.status(req).map(_ shouldBe Status.NoContent)
      }).sequence.unsafeRunSync()

      // Add new Contacts
      val description1 = "all contact (1)"
      val description2 = "all contact (2)"
      val importance1 = "high"
      val importance2 = "low"
      val id1 = createContact(description1, importance1)
      val id2 = createContact(description2, importance2)

      // Retrieve Contacts
      client.expect[Json](Uri.unsafeFromString(s"$urlStart/contacts")).unsafeRunSync shouldBe json"""
        [
          {
            "id": $id1,
            "description": $description1,
            "importance": $importance1
          },
          {
            "id": $id2,
            "description": $description2,
            "importance": $importance2
          }
        ]"""
    }
  }

  private def createContact(description: String, importance: String): Identity = {
    val createJson =json"""
      {
        "description": $description,
        "importance": $importance
      }"""
    val request = Request[IO](method = Method.POST, uri = Uri.unsafeFromString(s"$urlStart/contacts")).withBody(createJson).unsafeRunSync()
    val json = client.expect[Json](request).unsafeRunSync()
    root.id.string.getOption(json).nonEmpty shouldBe true
    Identity(root.id.string.getOption(json).get)
  }

  private def createServer(): IO[Http4sServer[IO]] = {
    for {
      transactor <- Database.transactor[IO](config.database)
      _          <- Database.initialize(transactor)
      repository =  ContactRepository(transactor)
      service    =  ContactService(repository)
      http       =  Logger(config.logging.logHeaders, config.logging.logBody)(service.http)
      server     <- BlazeBuilder[IO]
                      .bindHttp(config.server.port, config.server.host)
                      .mountService(http, "/").start
    } yield server
  }
}

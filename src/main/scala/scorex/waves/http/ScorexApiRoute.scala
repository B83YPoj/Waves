package scorex.waves.http

import javax.ws.rs.Path

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import io.swagger.annotations._
import play.api.libs.json.Json
import scorex.api.http.{ApiRoute, CommonApiFunctions, JsonResponse}
import scorex.app.Application
import scorex.consensus.mining.BlockGeneratorController._
import scorex.network.HistorySynchronizer
import scorex.utils.ScorexLogging
import scorex.waves.settings.Constants

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Path("/scorex")
@Api(value = "scorex", description = "General commands & information", position = 0)
case class ScorexApiRoute(override val application: Application)(implicit val context: ActorRefFactory)
  extends ApiRoute with CommonApiFunctions with ScorexLogging {

  override lazy val route =
    pathPrefix("scorex") {
      scorex ~ status ~ version
    }

  @Path("/version")
  @ApiOperation(value = "Version", notes = "get Scorex version", httpMethod = "GET")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json Scorex version")
  ))
  def version: Route = {
    path("version") {
      getJsonRoute {
        JsonResponse(Json.obj("version" -> Constants.AgentName), StatusCodes.OK)
      }
    }
  }

  @Path("/stop")
  @ApiOperation(value = "Stop", notes = "Stop the app", httpMethod = "POST")
  def scorex: Route = path("stop") {
    withAuth {
      postJsonRoute {
        log.info("Request to stop application")
        Future(application.stopAll())
        JsonResponse(Json.obj("stopped" -> true), StatusCodes.OK)
      }
    }
  }

  @Path("/status")
  @ApiOperation(value = "Status", notes = "Get status of the running core(Offline/Syncing/Generating)", httpMethod = "GET")
  def status: Route = path("status") {
    getJsonRoute {
      def bgf = (application.blockGenerator ? GetStatus).map(_.toString)
      def hsf = (application.historySynchronizer ? HistorySynchronizer.GetStatus).map(_.toString)

      Future.sequence(Seq(bgf, hsf)).map { case statusesSeq =>
        val json = Json.obj(
          "blockGeneratorStatus" -> statusesSeq.head,
          "historySynchronizationStatus" -> statusesSeq.tail.head
        )

        JsonResponse(json, StatusCodes.OK)
      }
    }
  }
}

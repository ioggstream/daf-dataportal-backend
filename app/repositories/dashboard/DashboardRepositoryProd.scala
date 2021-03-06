package repositories.dashboard

import java.io.File
import java.net.URL
import java.nio.file.{Files, StandardCopyOption}
import java.util.{Date, UUID}
import java.time.ZonedDateTime

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.mongodb
import com.mongodb.DBObject
import com.mongodb.casbah.Imports.{MongoCredential, MongoDBObject, ServerAddress}
import com.mongodb.casbah.MongoClient
import ftd_api.yaml.{Catalog, Dashboard, DashboardIframes, Filters, SearchResult, Success, UserStory}
import play.api.libs.json._
import play.api.libs.ws.ahc.AhcWSClient
import utils.ConfigReader

import scala.collection.immutable.List
import scala.concurrent.Future
import scala.io.Source
import scala.util.{Failure, Try}
import com.sksamuel.elastic4s.http.search.SearchResponse
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl.{highlight, rangeQuery, _}
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.searches.{RescoreDefinition, SearchDefinition}
import com.sksamuel.elastic4s.searches.queries._
import play.api.{Configuration, Environment, Logger}

import scala.reflect.runtime.universe


/**
  * Created by ale on 14/04/17.
  */

class DashboardRepositoryProd extends DashboardRepository {

  import ftd_api.yaml.BodyReads._
  import scala.concurrent.ExecutionContext.Implicits._

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  private val mongoHost: String = ConfigReader.getDbHost
  private val mongoPort = ConfigReader.getDbPort

  private val localUrl = ConfigReader.getLocalUrl

  private val securityManagerUrl = ConfigReader.securityManHost

  private val supersetUser = ConfigReader.getSupersetUser

  private val userName = ConfigReader.userName
  private val source = ConfigReader.database
  private val password = ConfigReader.password

  val server = new ServerAddress(mongoHost, 27017)
  val credentials = MongoCredential.createCredential(userName, source, password.toCharArray)

  val serverMongoMeta = new ServerAddress("metabase.default.svc.cluster.local", 27017)
  val credentialsMongoMeta = MongoCredential.createCredential("metabase", "metabase", "metabase".toCharArray)

  private val elasticsearchUrl = ConfigReader.getElasticsearchUrl
  private val elasticsearchPort = ConfigReader.getElasticsearchPort

  private val defaultOrg = "default_org"
  private val sharedStatus = 2
  private val draftStatus = 0

  val conf = Configuration.load(Environment.simple())
  val URLMETABASE = conf.getString("metabase.url").get
  val metauser = conf.getString("metabase.user").get
  val metapass = conf.getString("metabase.pass").get



  private def  metabaseTableInfo(iframes :Seq[DashboardIframes]): Future[Seq[DashboardIframes]] = {

    val wsClient = AhcWSClient()
    val futureFrames: Seq[Future[Try[DashboardIframes]]] = iframes.map { iframe =>
      val tableId = iframe.table
      val internalUrl = localUrl + s"/metabase/table/$tableId"
      // Missing metabase session not working
      val futureTry : Future[Try[DashboardIframes]]= wsClient.url(internalUrl).get()
       .map { resp =>
         println(resp.json)
         val tableName = (resp.json \ "name").as[String]
         iframe.copy(table = Some(tableName))
       }.map { x:DashboardIframes => scala.util.Success(x)}
        .recover { case t: Throwable => Failure(t) }

      futureTry
    }

    val seq: Future[Seq[Try[DashboardIframes]]] = Future.sequence(futureFrames)
    val dashboardIframes: Future[Seq[DashboardIframes]] = seq.map(
             _.filter(_.isSuccess)
              .map(_.get))
    dashboardIframes
    //val d: Future[Any] = seq.collect{ case scala.util.Success(x) => x}

  }

  def save(upFile: File, tableName: String, fileType: String): Success = {
    val message = s"Table created  $tableName"
    val fileName = new Date().getTime() + ".txt"
    val copyFile = new File(System.getProperty("user.home") + "/metabasefile/" + fileName + "_" + tableName)
    copyFile.mkdirs()
    val copyFilePath = copyFile.toPath
    Files.copy(upFile.toPath, copyFilePath, StandardCopyOption.REPLACE_EXISTING)
    //    val mongoClient = MongoClient(mongoHost, mongoPort)
    //    val db = mongoClient("monitor_mdb")
    //val mongoClient = MongoClient(server, List(credentials))
    //val db = mongoClient(source)
    //val mongoClient = MongoClient(serverMongoMeta, List(credentialsMongoMeta))
    //val db = mongoClient("metabase")
    val mongoClient = MongoClient(serverMongoMeta, List(credentialsMongoMeta))
    val db = mongoClient("metabase")
    val coll = db(tableName)
    if (fileType.toLowerCase.equals("json")) {
      val fileString = Source.fromFile(upFile).getLines().mkString
      val jsonArray: Option[JsArray] = DashboardUtil.toJson(fileString)
      val readyToBeSaved = DashboardUtil.convertToJsonString(jsonArray)
      readyToBeSaved.foreach(x => {
        val jsonStr = x.toString()
        val obj = com.mongodb.util.JSON.parse(jsonStr).asInstanceOf[DBObject]
        coll.insert(obj)
      })
    } else if (fileType.toLowerCase.equals("csv")) {
      val csvs = DashboardUtil.trasformMap(upFile)
      val jsons: Seq[JsObject] = csvs.map(x => Json.toJson(x).as[JsObject])
      jsons.foreach(x => {
        val jsonStr = x.toString()
        val obj = com.mongodb.util.JSON.parse(jsonStr).asInstanceOf[DBObject]
        coll.insert(obj)
      })
    }
    mongoClient.close()
    val meta = new MetabaseWs
    //meta.syncMetabase()
    Success(Some(message), Some("Good!!"))
  }

  def update(upFile: File, tableName: String, fileType: String): Success = {
    val message = s"Table updated  $tableName"
    val fileName = new Date().getTime() + ".txt"
    val copyFile = new File(System.getProperty("user.home") + "/metabasefile/" + fileName + "_" + tableName)
    copyFile.mkdirs()
    val copyFilePath = copyFile.toPath
    Files.copy(upFile.toPath, copyFilePath, StandardCopyOption.REPLACE_EXISTING)
    val fileString = Source.fromFile(upFile).getLines().mkString
    val jsonArray: Option[JsArray] = DashboardUtil.toJson(fileString)
    val readyToBeSaved = DashboardUtil.convertToJsonString(jsonArray)
    //val mongoClient = MongoClient(server, List(credentials))
    //val db = mongoClient(source)
    val mongoClient = MongoClient(serverMongoMeta, List(credentialsMongoMeta))
    val db = mongoClient("metabase")
    val coll = db(tableName)
    coll.drop()
    if (fileType.toLowerCase.equals("json")) {
      readyToBeSaved.foreach(x => {
        val jsonStr = x.toString()
        val obj = com.mongodb.util.JSON.parse(jsonStr).asInstanceOf[DBObject]
        coll.insert(obj)
      })
    } else if (fileType.toLowerCase.equals("csv")) {
      val csvs = DashboardUtil.trasformMap(upFile)
      val jsons: Seq[JsObject] = csvs.map(x => Json.toJson(x).as[JsObject])
      jsons.foreach(x => {
        val jsonStr = x.toString()
        val obj = com.mongodb.util.JSON.parse(jsonStr).asInstanceOf[DBObject]
        coll.insert(obj)
      })
    }
    mongoClient.close()
    Success(Some(message), Some("Good!!"))
  }

  def tables(): Seq[Catalog] = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val collections = db.collectionNames()
    val catalogs = collections.map(x => Catalog(Some(x))).toSeq
    mongoClient.close()
    catalogs
  }




  def iframesByOrg(user: String,org: String): Future[Seq[DashboardIframes]] = {

    val result = for {
      tables <- getSupersetTablesByOrg(org)
      a <- iFramesByTables(user,tables)
    } yield a

    result

  }

  private def getSupersetTablesByOrg(org: String): Future[Seq[String]] = {

    val wsClient = AhcWSClient()
    //security-manager/v1/internal/superset/find_orgtables/default_org

    val internalUrl = securityManagerUrl + "/security-manager/v1/internal/superset/find_orgtables/"+org
    println("internalUrl: "+internalUrl)

    wsClient.url(internalUrl).get().map{ resp =>
      println("getSupersetTablesByOrg response: "+resp)
      (resp.json \ "tables").as[Seq[String]]
    }

  }

  private def iFramesByTables(user: String, tables:Seq[String]): Future[Seq[DashboardIframes]]= {
    iframes(user).map{ _.filter(dash => dash.table.nonEmpty && tables.contains(dash.table.get) ) }
  }

  def iframes(user: String): Future[Seq[DashboardIframes]] = {
    println("-iframes-")
    val wsClient = AhcWSClient()

    val metabasePublic = localUrl + "/metabase/public_card/" + user
    val supersetPublic = localUrl + "/superset/public_slice/" + user
    val grafanaPublic = localUrl + "/grafana/snapshots/" + user
    val tdmetabasePublic = localUrl + "/tdmetabase/public_card"

    val request = wsClient.url(metabasePublic).get()
    // .andThen { case _ => wsClient.close() }
    // .andThen { case _ => system.terminate() }

    val requestIframes = wsClient.url(supersetPublic).get()
    //  .andThen { case _ => wsClient.close() }
    //  .andThen { case _ => system.terminate() }

    val requestSnapshots = wsClient.url(grafanaPublic).get()

    val requestTdMetabase = wsClient.url(tdmetabasePublic).get


    val superset: Future[Seq[DashboardIframes]] = requestIframes.map { response =>
      val json = response.json.as[Seq[JsValue]]
      val iframes = json.map(x => {
        val slice_link = (x \ "slice_link").get.as[String]
        val vizType = (x \ "viz_type").get.as[String]
        val title = slice_link.slice(slice_link.indexOf(">") + 1, slice_link.lastIndexOf("</a>")).trim
        val src = slice_link.slice(slice_link.indexOf("\"") + 1, slice_link.lastIndexOf("\"")) + "&standalone=true"
        val url = ConfigReader.getSupersetUrl + src
        val decodeSuperst = java.net.URLDecoder.decode(url, "UTF-8");
        val uri = new URL(decodeSuperst)
        val queryString = uri.getQuery.split("&", 2)(0)
        val valore = queryString.split("=", 2)(1)

        if (valore.contains("{\"code") || vizType.equals("separtor") || vizType.equals("filter")) {
          DashboardIframes(None, None, None, None, None, None)
        } else {
          try {
            val identifierJson = Json.parse(s"""$valore""")
            val slice_id = (identifierJson \ "slice_id").asOpt[Int].getOrElse(0)
            val table = (x \ "datasource_link").get.asOpt[String].getOrElse("").split(">").last.split("<").head
            DashboardIframes( Some("superset_" + slice_id.toString), Some(url),Some(vizType), Some("superset"), Some(title), Some(table) )
          } catch {
            case e: Exception => e.printStackTrace(); println("ERROR"); DashboardIframes(None, None, None, None, None, None)
          }
        }
      })

      iframes.filter {
        case DashboardIframes(Some(_), Some(_), Some(_), Some(_), Some(_), Some(_)) => true
        case _ => false
      }
    }

    val metabase: Future[Seq[DashboardIframes]] = request.map { response =>
      val json = response.json.as[Seq[JsValue]]

      Logger.debug(s"Metabase iframe response: $json")
      json.filter( x => !(x \ "public_uuid").asOpt[String].isEmpty)
        .map(x => {
         val uuid = (x \ "public_uuid").get.as[String]
         val title = (x \ "name").get.as[String]
      //  val id = (x \ "id").get.as[String]
         val tableId =   (x \ "table_id").get.as[Int]
         val url = ConfigReader.getMetabaseUrl + "/public/question/" + uuid
         DashboardIframes( Some("metabase_" + uuid), Some(url), None, Some("metabase"), Some(title), Some(tableId.toString))
      })
    }



    //val test: Future[Seq[DashboardIframes]] = for {
    //  iframes <- metabase
    //  iframesWithTable <- metabaseTableInfo(iframes)
    //} yield iframesWithTable



     val tdMetabase  :Future[Seq[DashboardIframes]] = requestTdMetabase.map { response =>
       val json = response.json.as[Seq[JsValue]]

       Logger.debug(s"tMetabase iframe response: $json")
       json.map( x => {
         val uuid = (x \ "public_uuid").get.as[String]
         val title = (x \ "name").get.as[String]
         val url = ConfigReader.getTdMetabaseURL + "/public/question/" + uuid
         DashboardIframes( Some("metabase_" + uuid), Some(url), None, Some("metabase"), Some(title), None)
         //DashboardIframes(Some(url), Some("tdmetabase"), Some(title), Some("tdmetabase_" + uuid))
       })
     }


    val grafana: Future[Seq[DashboardIframes]] = requestSnapshots.map { response =>
      val json = response.json.as[Seq[JsValue]]
      json.map(x => {
        println("QUI VEDIAMO")
        println(x)
        val uuid = (x \ "key").get.as[String]
        val id = (x \ "id").get.as[Int]
        val title = (x \ "name").get.as[String]
        val url = ConfigReader.getGrafanaUrl + "/dashboard/snapshot/" + uuid
        DashboardIframes( Some("grafana_" + id.toString), Some(url),None,Some("grafana"), Some(title), None)
      })
    }


    val services  = List(metabase, superset, grafana, tdMetabase)

    def futureToFutureTry[T](f: Future[T]): Future[Try[T]] =
      f.map(scala.util.Success(_)).recover { case t: Throwable => Failure(t) }

    val withFailed: Seq[Future[Try[Seq[DashboardIframes]]]] = services.map(futureToFutureTry(_))

    // Can also be done more concisely (but less efficiently) as:
    // f.map(Success(_)).recover{ case t: Throwable => Failure( t ) }
    // NOTE: you might also want to move this into an enrichment class
    //def mapValue[T]( f: Future[Seq[T]] ): Future[Try[Seq[T]]] = {
    //  val prom = Promise[Try[Seq[T]]]()
    //  f onComplete prom.success
    //  prom.future
    //}

    val servicesWithFailed = Future.sequence(withFailed)

    val servicesSuccesses: Future[Seq[Try[Seq[DashboardIframes]]]] = servicesWithFailed.map(_.filter(_.isSuccess))

    val results: Future[Seq[DashboardIframes]] = servicesSuccesses.map(_.flatMap(_.toOption).flatten)

    results
  }

  def dashboards(groups: List[String], status: Option[Int]): Seq[Dashboard] = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("dashboards")
    val query = status match {
      case Some(x) => MongoDBObject("published" -> x)
      case None => MongoDBObject()
    }
    val results = coll.find(query).sort(MongoDBObject("timestamp" -> -1)).toList
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(results)
    val json = Json.parse(jsonString)
    println(json)
    val dashboardsJsResult = json.validate[Seq[Dashboard]]
    val dashboards = dashboardsJsResult match {
      case s: JsSuccess[Seq[Dashboard]] => s.get
      case _: JsError => Seq()
    }
    dashboards
      .filter(dash => dash.org.get.equals(defaultOrg) || groups.contains(dash.org.get))
  }

  def dashboardsPublic(status: Option[Int]): Seq[Dashboard] = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val query = status match {
      case Some(x) => MongoDBObject("published" -> x)
      case None => MongoDBObject()
    }
    val coll = db("dashboards")
    val results = coll.find(query).toList
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(results)
    val json = Json.parse(jsonString)
    println(json)
    val dashboardsJsResult = json.validate[Seq[Dashboard]]
    val dashboards = dashboardsJsResult match {
      case s: JsSuccess[Seq[Dashboard]] => s.get
      case e: JsError => Seq()
    }
    dashboards.filter(dash => dash.org.get.equals(defaultOrg) && dash.status.get == sharedStatus)
  }


  def dashboardById(groups: List[String], id: String): Dashboard = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("dashboards")
    //  val objectId = new org.bson.types.ObjectId(id)
    val query = MongoDBObject("id" -> id)
    // val query = MongoDBObject("title" -> id)
    val result = coll.findOne(query)
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(result)
    val json = Json.parse(jsonString)
    val dashboardJsResult: JsResult[Dashboard] = json.validate[Dashboard]
    val dashboard: Dashboard = dashboardJsResult match {
      case s: JsSuccess[Dashboard] => s.get
      case e: JsError => Dashboard(None, None, None, None, None, None, None, None, None, None)
    }
    val organization = dashboard.org.get
    if(organization.equals(defaultOrg) || groups.contains(organization)) dashboard
    else Dashboard(None, None, None, None, None, None, None, None, None, None)
  }

  def publicDashboardById(id: String): Dashboard = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("dashboards")
    //  val objectId = new org.bson.types.ObjectId(id)
    val query = MongoDBObject("id" -> id)
    // val query = MongoDBObject("title" -> id)
    val result = coll.findOne(query)
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(result)
    val json = Json.parse(jsonString)
    val dashboardJsResult: JsResult[Dashboard] = json.validate[Dashboard]
    val dashboard: Dashboard = dashboardJsResult match {
      case s: JsSuccess[Dashboard] => s.getOrElse(Dashboard(None, None, None, None, None, None, None, None, None, None))
      case e: JsError => Dashboard(None, None, None, None, None, None, None, None, None, None)
    }
    if(dashboard.org.get.equals(defaultOrg) && dashboard.status.get == sharedStatus) dashboard
    else Dashboard(None, None, None, None, None, None, None, None, None, None)
  }

  def saveDashboard(dashboard: Dashboard, user: String): Success = {
    import ftd_api.yaml.ResponseWrites.DashboardWrites
    val id = dashboard.id
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("dashboards")
    var saved = "Not Saved"
    var operation = "Not Saved"
    id match {
      case Some(x) => {
        val json: JsValue = Json.toJson(dashboard)
        val obj = com.mongodb.util.JSON.parse(json.toString()).asInstanceOf[DBObject]
        val query = MongoDBObject("id" -> x)
        saved = id.get
        operation = "updated"
        val a: mongodb.casbah.TypeImports.WriteResult = coll.update(query, obj)
      }
      case None => {
        dashboard.title match {
          case Some(x) =>
            val uid = UUID.randomUUID().toString
            val timestamps = ZonedDateTime.now()
            val newDash = dashboard.copy(id = Some(uid), user = Some(user), timestamp = Some(timestamps))
            val json: JsValue = Json.toJson(newDash)
            val obj = com.mongodb.util.JSON.parse(json.toString()).asInstanceOf[DBObject]
            saved = uid
            operation = "inserted"
            coll.save(obj)
        }
      }
    }
    mongoClient.close()
    val response = Success(Some(saved), Some(operation))
    response

  }

  def deleteDashboard(dashboardId: String): Success = {
    val query = MongoDBObject("id" -> dashboardId)
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("dashboards")
    val removed = coll.remove(query)
    val response = Success(Some("Deleted"), Some("Deleted"))
    response
  }

  def stories(groups: List[String], status: Option[Int], page: Option[Int], limit: Option[Int]): Seq[UserStory] = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val query = status match {
      case Some(x) => MongoDBObject("published" -> x)
      case None => MongoDBObject()
    }
    val coll = db("stories")
    val results = coll.find(query)
      .sort(MongoDBObject("timestamp" -> -1))
      .skip(page.getOrElse(0))
      .limit(limit.getOrElse(100)).toList
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(results)
    val json = Json.parse(jsonString)
    println(json)
    val storiesJsResult = json.validate[Seq[UserStory]]
    val stories = storiesJsResult match {
      case s: JsSuccess[Seq[UserStory]] => s.get
      case e: JsError => Seq()
    }
    stories.filter(
      story => story.org.get.equals(defaultOrg) || groups.contains(story.org.get)
    )

  }

  def storiesPublic(status: Option[Int]): Seq[UserStory] = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val query = status match {
      case Some(x) => MongoDBObject("published" -> x)
      case None => MongoDBObject()
    }
    val coll = db("stories")
    val results = coll.find(query).sort(MongoDBObject("timestamp" -> -1)).toList
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(results)
    val json = Json.parse(jsonString)
    println(json)
    val storiesJsResult = json.validate[Seq[UserStory]]
    val stories = storiesJsResult match {
      case s: JsSuccess[Seq[UserStory]] => s.get
      case e: JsError => Seq()
    }
    stories.filter(story => story.org.get.equals(defaultOrg) && story.published.getOrElse(draftStatus) == sharedStatus)
  }

  def storyById(groups: List[String], id: String): UserStory = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("stories")
    //  val objectId = new org.bson.types.ObjectId(id)
    val query = MongoDBObject("id" -> id)
    // val query = MongoDBObject("title" -> id)
    val result = coll.findOne(query)
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(result)
    val json = Json.parse(jsonString)
    val storyJsResult: JsResult[UserStory] = json.validate[UserStory]
    val story: UserStory = storyJsResult match {
      case s: JsSuccess[UserStory] => s.get
      case e: JsError => UserStory(None, None, None, None, None, None, None, None, None, None)
    }
    val organization = story.org.get
    if(organization.equals(defaultOrg) || groups.contains(organization)) story
    else UserStory(None, None, None, None, None, None, None, None, None, None)
  }

  def publicStoryById(id: String): UserStory = {
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("stories")
    //  val objectId = new org.bson.types.ObjectId(id)
    val query = MongoDBObject("id" -> id)
    // val query = MongoDBObject("title" -> id)
    val result = coll.findOne(query)
    mongoClient.close
    val jsonString = com.mongodb.util.JSON.serialize(result)
    val json = Json.parse(jsonString)
    val storyJsResult: JsResult[UserStory] = json.validate[UserStory]
    val story: UserStory = storyJsResult match {
      case s: JsSuccess[UserStory] => s.get
      case _: JsError => UserStory(None, None, None, None, None, None, None, None, None, None)
    }
    if(story.org.get.equals(defaultOrg) && story.published.getOrElse(draftStatus) == sharedStatus) story
    else UserStory(None, None, None, None, None, None, None, None, None, None)
  }

  def saveStory(story: UserStory, user: String): Success = {
    import ftd_api.yaml.ResponseWrites.UserStoryWrites
    val id = story.id
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("stories")
    var saved = "Not Saved"
    var operation = "Not Saved"
    id match {
      case Some(x) => {
        val json: JsValue = Json.toJson(story)
        val obj = com.mongodb.util.JSON.parse(json.toString()).asInstanceOf[DBObject]
        val query = MongoDBObject("id" -> x)
        saved = id.get
        operation = "updated"
        coll.update(query, obj)
      }
      case None => {
        story.title match {
          case Some(x) =>
            val uid = UUID.randomUUID().toString
            val timestamps = ZonedDateTime.now()
            val newStory = story.copy(id = Some(uid), user = Some(user), timestamp = Some(timestamps))
            val json: JsValue = Json.toJson(newStory)
            val obj = com.mongodb.util.JSON.parse(json.toString()).asInstanceOf[DBObject]
            saved = uid
            operation = "inserted"
            coll.save(obj)
        }

      }
    }

    mongoClient.close()
    val response = Success(Some(saved), Some(operation))
    response
  }

  def deleteStory(storyId: String): Success = {
    val query = MongoDBObject("id" -> storyId)
    val mongoClient = MongoClient(server, List(credentials))
    val db = mongoClient(source)
    val coll = db("stories")
    val removed = coll.remove(query)
    val response = Success(Some("Deleted"), Some("Deleted"))
    response
  }

  def searchText(filters: Filters, username: String, groups: List[String]): Seq[SearchResult] = {
    val client = HttpClient(ElasticsearchClientUri(elasticsearchUrl, elasticsearchPort))
    val index = "_all"
    val fieldDatasetDcatName = "dcatapit.name"
    val fieldDatasetDcatTitle = "dcatapit.title"
    val fieldDatasetDcatNote = "dcatapit.notes"
    val fieldDatasetDcatTheme = "dcatapit.theme"
    val fieldDatasetDataFieldName = "dataschema.avro.fields.name"
    val fieldUsDsTitle = "title"
    val fieldUsDsSub = "subtitle"
    val fieldUsDsWget = "widgets"
    val fieldDataset = List(fieldDatasetDcatName, fieldDatasetDcatTitle, fieldDatasetDcatNote,
      fieldDatasetDataFieldName, fieldDatasetDcatTheme, "dcatapit.privatex", "dcatapit.modified", "dcatapit.owner_org")
    val fieldDashboard = listFields("Dashboard")
    val fieldStories = listFields("User-Story")
    val fieldToReturn = fieldDataset ++ fieldDashboard ++ fieldStories
    val fieldAggr = "type"

    val listFieldSearch = List(fieldDatasetDcatName, fieldDatasetDcatTitle, fieldDatasetDcatNote,
      fieldDatasetDataFieldName, fieldUsDsTitle, fieldUsDsSub, fieldUsDsWget, "dcatapit.owner_org", "org")

    val searchString = filters.text match {
      case Some("") => ".*"
      case Some(x) => ".*" + x + ".*"
      case None => ".*"
    }

    val searchType = filters.index match {
      case Some(List()) => ""
      case Some(x) => x.mkString(",")
      case None => ""
    }

    val order = filters.order match {
      case Some("score") => "score"
      case Some("asc") => "asc"
      case _ => "desc"
    }

    def queryElasticsearch = {

      search(index).types(searchType).query(
        boolQuery()
          .must(
            should(
              searchString.split(" ").flatMap(s => listFieldSearch.map(field => regexQuery(field, s)))
            ),
            must(
              creteThemeFilter(filters.theme, fieldDatasetDcatTheme) ::: createFilterOrg(filters.org) :::
                createFilterStatus(filters.status) ::: createFilterDate(filters.date)
            ),
            should(
              must(matchQuery("dcatapit.privatex", "1"), matchQuery("dcatapit.owner_org", groups.mkString(" "))),
              matchQuery("dcatapit.privatex", "0"),
              must(matchQuery("status", "0"), matchQuery("user", username)),
              must(matchQuery("published", "0"), matchQuery("user", username)),
              must(matchQuery("status", "1"), matchQuery("org", groups.mkString(" "))),
              must(matchQuery("published", "1"), matchQuery("org", groups.mkString(" "))),
              matchQuery("status", "2"),
              matchQuery("published", "2")
            )
          )
      )
        .limit(10000)
    }

    val query: SearchDefinition = queryElasticsearch
    val res = client.execute{
      query
        .aggregations(termsAgg(fieldAggr, "_type"), termsAgg("category", "dcatapit.theme.keyword"),
          termsAgg("org_1", "dcatapit.owner_org.keyword"), termsAgg("org_2", "org.keyword"),
          termsAgg("stat_1", "status"), termsAgg("stat_2", "published"), termsAgg("stat_3", "dcatapit.privatex"))
        .sourceInclude(fieldToReturn)
        .highlighting(listFieldSearch.map(x => highlight(x).preTag("<span style='background-color:#0BD9D3'>").postTag("</span>").fragmentSize(70)))
    }.await

    client.close()

    wrapResponse(res, order, !searchString.equals(".*")) ++ createAggResp(res).sortBy(x => x.`type`).reverse
  }

  private def createFilterStatus(status: Option[Seq[String]]): List[QueryDefinition] = {
    status match {
      case Some(List()) => List()
      case Some(x) => {
        val statusDataset = x.map {
          case "2" => "0"
          case _ => "1"
        }
        List(should(termsQuery("dcatapit.privatex", statusDataset), termsQuery("status", status.get), termsQuery("published", status.get)))
      }
      case _ => List()
    }
  }

  private def createFilterOrg(inputOrgFilter: Option[Seq[String]]) = {
    val datasetOrg = "dcatapit.owner_org"
    val dashNstorOrg = "org"

    inputOrgFilter match {
      case Some(Seq()) => List()
      case Some(org) => List(should(matchQuery(datasetOrg, inputOrgFilter), matchQuery(dashNstorOrg, org.mkString(" "))))
      case _ => List()
    }
  }

  private def creteThemeFilter(theme: Option[Seq[String]], fieldDatasetDcatTheme: String): List[BoolQueryDefinition] = {
    theme match {
      case Some(Seq()) => List()
      case Some(t) => List(should(matchQuery(fieldDatasetDcatTheme, t.mkString(" "))))
      case _ => List()
    }
  }

  private def createFilterDate(timestamp: Option[String]): List[QueryDefinition] = {
    val datasetDate = "dcatapit.modified"
    val dashNstoriesDate = "timestamp"

    timestamp match {
      case Some("") => List()
      case Some(dates) => List(should(rangeQuery(datasetDate).gte(dates.split(" ")(0)).lte(dates.split(" ")(1)),
        rangeQuery(dashNstoriesDate).gte(dates.split(" ")(0)).lte(dates.split(" ")(1))
      ))
      case _ => List()
    }
  }

  private def wrapResponse(query: SearchResponse, order: String, search: Boolean): Seq[SearchResult] = {
    val seqSearchResult: Seq[SearchResult] = query.hits.hits.map(source =>
      SearchResult(Some(source.`type`), Some(source.sourceAsString),
        if(search){
          Some(
            "{" +
              source.highlight.map(x =>
                x._1 match {
                  case "widgets" => s""""${x._1}": "${x._2.mkString("...").replace("\"", "\\\"")}""""
                  case _ => s""""${x._1}": "${x._2.mkString("...")}""""
                }
              ).mkString(",")
              + "}")
        } else Some("{}")
      )
    ).toSeq

    val tupleDateSearchResult: List[(String, SearchResult)] = seqSearchResult.map(x =>
      (
        x.source.get.split(",").map(
          f => if (f.contains("\"timestamp\"") || f.contains("\"modified\""))
            f.split(":")(1)
          else "").toList
          .filterNot(w => w.equals(""))(0), x)
    ).toList

    val result = order match {
      case "score" => tupleDateSearchResult
      case "asc" => tupleDateSearchResult.sortWith(_._1 < _._1)
      case _ => tupleDateSearchResult.sortWith(_._1 > _._1)
    }

    result.map(elem => elem._2)
  }

  private def aggToSearchResult(mapAgg: Map[String, Map[String, Int]]): Seq[SearchResult] = {
    val mapOrg = mergeAgg(mapAgg, "organization", "org_1", "org_2", "")
    val mapStatus = mergeAgg(mapAgg, "status", "stat_1", "stat_2", "stat_3")

    (mapAgg.filterNot(s => s._1.equals("org_1") || s._1.equals("org_2") ||
      s._1.equals("stat_1") || s._1.equals("stat_2") || s._1.equals("stat_3")) ++ mapOrg ++ mapStatus).map(
      elem => SearchResult(Some(elem._1), Some("{" + elem._2.map(v => s""""${v._1}":"${v._2}"""").mkString(",") + "}"), None)
      ).toSeq
  }

  private def createAggResp(query: SearchResponse): Seq[SearchResult] = {
    val mapAgg: Map[String, Map[String, Int]] = query.aggregations.map{ elem =>
      val name = elem._1
      val valueMap = elem._2.asInstanceOf[Map[String, Any]]("buckets").asInstanceOf[List[Map[String, AnyVal]]]
          .map(elem =>
            wrapAggrResp(elem.values.toList)
          )
        .map(v => v._1 -> v._2).toMap
      name -> valueMap
    }
    aggToSearchResult(mapAgg)
  }

  private def parseMapAgg(map: Map[String, Int]): Map[String, Int] = {
    if(map.contains("true") || map.contains("false")) {
      val countTrue: Int = map.getOrElse("true", 0)
      val countFalse: Int = map.getOrElse("false", 0)
      val listT: List[(String, Int)] = if(map.contains("true")) List(("0", countTrue), ("1", countTrue)) else List()
      val listF: List[(String, Int)] = if(map.contains("false")) List(("2", countFalse)) else List()
      (listT ++ listF).map(elem => elem._1 -> elem._2).toMap
    } else
      map
  }

  private def mergeAgg(mapOrg: Map[String, Map[String, Int]], key: String, nameMap1: String, nameMap2: String, nameMap3: String): Map[String, Map[String, Int]] = {
    val map1: Map[String, Int] = parseMapAgg(mapOrg.get(nameMap1).getOrElse(Map()))
    val map2: Map[String, Int] = parseMapAgg(mapOrg.get(nameMap2).getOrElse(Map()))
    val map3: Map[String, Int] = parseMapAgg(mapOrg.get(nameMap3).getOrElse(Map()))

    val listKyes = map1.keySet ++ map2.keySet ++ map3.keySet

    val res = listKyes.map(k =>
      k -> (map1.getOrElse(k, 0) + map2.getOrElse(k, 0) + map3.getOrElse(k, 0))
    ).map(v => v._1 -> v._2).toMap

    Map(key -> res)
  }

  private def wrapAggrResp(listCount: List[AnyVal]): (String, Int) = {
    if(listCount.length == 2) (listCount(0).toString, listCount(1).asInstanceOf[Int])
    else {
      val key = listCount(1).toString
      val count = listCount(2).asInstanceOf[Int]
      (key, count)
    }
  }

  private def listFields(obj: String): List[String] = {
    import scala.reflect.runtime.universe._
    val objType: universe.Type = obj match {
      case "Dashboard" => typeOf[Dashboard]
      case "User-Story" => typeOf[UserStory]
    }
    val fields = objType.members.collect{
      case m: MethodSymbol if m.isCaseAccessor => m.name.toString
    }.toList
    fields
  }
}

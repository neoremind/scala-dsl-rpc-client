import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import _root_.com.baidu.ub.msoa.container.support.governance.annotation.BundleService
import _root_.com.baidu.ub.msoa.governance.agent.utils.JSONUtil
import _root_.com.baidu.ub.msoa.plugin.protostuff.ProtoStuffReflect
import _root_.com.baidu.ub.msoa.rpc.domain.dto.ErrorInfo
import _root_.com.baidu.ub.msoa.rpc.plugin.navi2.codec.Navi2ProtoCodec
import _root_.com.ning.http.client
import _root_.com.ning.http.client.{AsyncCompletionHandler, Response}
import _root_.com.typesafe.config._
import dispatch._
import msoa.org.apache.commons.collections.CollectionUtils
import msoa.org.apache.commons.lang3.{ArrayUtils, StringUtils}

import scala.collection.JavaConversions
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
 * MSOA微服务化DSL方式调用客户端
 * <p/>
 * DSL语法：
 * <pre>
 * msoa.MSOA {on "IP:PORT"} [listserver/desc/call/tojson] classOf[XXXClazz] method(args)
 * </pre>
 *
 * @param isOnline 是否为线上的治理中心
 * @param endPoint 访问地址IP:PORT，默认为None
 */
class MSOA(var isOnline: Boolean = false, var endPoint: Option[String] = None) extends UrlDefine {

  /**
   * 调用某个微服务
   *
   * @param t 微服务的类型，用classOf[XXX]表示
   * @return 微服务访问代理
   */
  def call[T](t: Class[T]): T = {
    Proxy.newProxyInstance(Thread.currentThread.getContextClassLoader, Array[Class[_]](t), DynamicStubProxy[T]
      (isOnline, endPoint)).asInstanceOf[T]
  }

  /**
   * 指定访问地址的方式，返回一个多例的新的MSOA
   *
   * @param e 访问地址
   * @return MSOA
   */
  def on(e: String): MSOA = {
    if (StringUtils.isEmpty(e)) {
      throw EndpointException("If `on` verb used, endpoint should NOT be empty.")
    }
    new MSOA(this.isOnline, Some(e))
  }

  /**
   * 列出可用的服务IP:PORT列表
   *
   * @param t  微服务的类型，用classOf[XXX]表示
   * @return 微服务访问代理
   */
  def listserver[T](t: Class[T]): T = {
    Proxy.newProxyInstance(Thread.currentThread.getContextClassLoader, Array[Class[_]](t), new
        DynamicGovernanceProxy(isOnline)).asInstanceOf[T]
  }

  /**
   * 列出某个服务的全部方法
   *
   * @param t 微服务的类型，用classOf[XXX]表示
   */
  def desc[T](t: Class[T]): Unit = {
    t.getMethods.toIterator.foreach(m => {
      println("------------------------------\n" + m.toString
        .replaceFirst("public abstract ", "")
        .replaceAll("java.lang.", "")
        .replaceAll(t.getName + ".", ""))
    })
  }

  /**
   * 默认打印pretty json
   *
   * @param a
   * @return pretty json
   */
  def tojson(a: AnyRef): String = {
    tojson(a, true)
  }

  /**
   * 打印json
   *
   * @param a 对象
   * @param isPretty 是否为格式化好的pretty json
   * @return json
   */
  def tojson(a: AnyRef, isPretty: Boolean): String = {
    if (isPretty) {
      import com.fasterxml.jackson.databind._
      val mapper: ObjectMapper = new ObjectMapper
      val ret = mapper.writerWithDefaultPrettyPrinter[ObjectWriter]().writeValueAsString(a)
      println(ret)
      ret
    } else {
      val ret = JSONUtil.toString(a)
      println(ret)
      ret
    }
  }

}

/**
 * MSOA微服务化DSL方式调用的伴生对象
 */
object MSOA extends MSOA(
  false, Some("")
) {

}

/**
 * 动态代理访问远程微服务的jdk proxy
 *
 * @param isOnline 是否为线上服务治理中心，默认为线下
 * @param endPoint 调用点IP:PORT，可以为None
 */
case class DynamicStubProxy[A](val isOnline: Boolean, var endPoint: Option[String]) extends InvocationHandler with UrlDefine with Constans {

  /**
   * 序列化协议，默认使用protostuff
   */
  private val CODEC: Navi2ProtoCodec = new Navi2ProtoCodec

  /**
   * Processes a method invocation on a proxy instance and returns
   * the result.  This method will be invoked on an invocation handler
   * when a method is invoked on a proxy instance that it is
   * associated with.
   *
   * @param proxy 代理对象
   * @param method 代理方法
   * @param args 方法参数
   * @return 远程访问结果
   */
  override def invoke(proxy: Object, method: Method, args: Array[Object]): Object = {
    // 获取Stub SDK上的BundleService注解
    val bundle = method.getDeclaringClass.getAnnotation(classOf[BundleService])

    // 如果没有设置访问地址的endpoint，则从治理中心获取一个
    if (endPoint.isEmpty || StringUtils.isEmpty(endPoint.get)) {
      endPoint = Some(GovernanceHelper.getOneEndPoint(isOnline, bundle, method.getName))
    }

    // 构造HTTP请求，包括header以及body，发送POST-HTTP请求到Restful API的服务server
    // 使用Dispatch框架，以及async-http-client和netty做通讯层
    // 返回future回执
    val URL = toServiceUrl(endPoint.get, bundle, method.getName)
    println("Start to call " + URL)
    val req = url(URL).POST
      .addHeader("Content-Type", "application/proto")
      .addHeader("navi2.requestid", "-1")
      .addHeader("navi2.traceid", "9999")
      .addHeader("navi2.spanid", "999")
      .addHeader("navi2.userid", "0")
      .setBody(if (ArrayUtils.isEmpty(args)) new Array[Byte](0) else CODEC.encode(args))
    val response: Future[ResponseWithSC] = Http.configure(_ setConnectionTimeoutInMs CONNECT_TIMEOUT_IN_MILLISECONDS)(MSOARequestHandlerTupleBuilder
      (req).build[ResponseWithSC]
      (BytesWithSC))

    // 异步非阻塞方式，进行response future的回调，使用模式匹配来打印一些日志
    response onComplete {
      case Success(content) => {
        printf("Successfully call %s.%s\n", method.getDeclaringClass.getSimpleName, method.getName)
      }
      case Failure(t) => {
        throw MSOACallException("An error has occured when calling: " + t.getMessage)
      }
    }

    // 等待请求返回后，序列化还原成对象，返回方法
    // 对于http status = 500的响应，还原异常抛出
    Await.result(response, Duration(READ_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS))
    val responseWithSC = response.value.get.get
    if (responseWithSC.statusCode == 200) {
      CODEC.decode(responseWithSC.bytes, method.getReturnType).asInstanceOf[Object]
    } else {
      val errorInfo = CODEC.decode(responseWithSC.bytes, classOf[ErrorInfo])
      val clazz = Thread.currentThread.getContextClassLoader.loadClass(errorInfo.exceptionType).asInstanceOf[Class[_ <: Throwable]]
      throw ProtoStuffReflect.decode(errorInfo.throwable, clazz)
    }
  }

}

/**
 * 动态代理访问服务治理中心的jdk proxy
 *
 * @param isOnline 是否为线上服务治理中心，默认为线下
 */
case class DynamicGovernanceProxy(val isOnline: Boolean) extends InvocationHandler {

  /**
   * Processes a method invocation on a proxy instance and returns
   * the result.  This method will be invoked on an invocation handler
   * when a method is invoked on a proxy instance that it is
   * associated with.
   *
   * @param proxy 代理对象
   * @param method 代理方法
   * @param args 方法参数
   * @return 远程访问结果
   */
  override def invoke(proxy: Object, method: Method, args: Array[Object]): Object = {
    val bundle = method.getDeclaringClass.getAnnotation(classOf[BundleService])
    printf("Available endpoints for %s are: \n------------------\n", method.getDeclaringClass.getName)
    GovernanceHelper.getAllEndPoints(isOnline, bundle, method.getName).foreach(println _)
    null
  }

}

/**
 * 治理中心帮助类
 */
class GovernanceHelper extends UrlDefine with Constans {

  /**
   * 获取某个微服务的所有节点
   *
   * @param isOnline 是否为线上的治理中心
   * @param bundle  微服务接口上的注解BundleService，包括provider、service和version
   * @param methodName 方法名称
   * @return 节点host:port列表
   */
  def getAllEndPoints(isOnline: Boolean, bundle: BundleService, methodName: String): List[String] = {
    import _root_.com.baidu.ub.msoa.governance.agent.domain.dto.ServiceTopologyResponse

    // 从properties中获取服务治理中心地址，通过特指里的ONLINE来区分线上线下
    val conf: Config = ConfigFactory.load()
    val governanceCenterAddress =
      if (isOnline) conf.getString("msoa.governance.center.online.address") else conf.getString("msoa.governance.center.offline.address")

    // 访问治理中心API
    val URL = toEndPointDiscoveryUrl(governanceCenterAddress, bundle, methodName)
    println("Try to get endpoints from " + URL)
    val req = url(URL)
      .GET
      .addHeader("Content-Type", "application/json")
    val response: Future[Array[Byte]] = Http(MSOARequestHandlerTupleBuilder(req).build(Bytes))

    // 异步非阻塞获取结果，回调
    response onComplete {
      case Success(content) => {
        //println("Successful response string: " + content)
      }
      case Failure(t) => {
        throw MSOACallException("An error has occured when trying to get endpoint from governance center: " + t.getMessage)
      }
    }

    // 等等结果返回
    Await.result(response, Duration(READ_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS))
    val res = JSONUtil.toObject(response.value.get.get, classOf[ServiceTopologyResponse])

    // 解析结果
    if (res.isSuccess) {
      if (res.getTopologyView.isAvailable) {
        if (CollectionUtils.isEmpty(res.getTopologyView.getEndpoints))
          throw ServiceTopologyException("No service is alive")
        JavaConversions.asScalaSet(res.getTopologyView.getEndpoints).map(_.address()).toList
      } else {
        throw ServiceTopologyException("Topology is not available")
      }
    } else {
      throw ServiceTopologyException("Failed to get topology from governance center")
    }
  }

  /**
   * 获取某个微服务的其中之一节点
   *
   * @param isOnline 是否为线上的治理中心
   * @param bundle  微服务接口上的注解BundleService，包括provider、service和version
   * @param methodName 方法名称
   * @return 节点host:port
   */
  def getOneEndPoint(isOnline: Boolean, bundle: BundleService, methodName: String): String = {
    getAllEndPoints(isOnline, bundle, methodName)(0)
  }

}

/**
 * 治理中心帮助类伴生对象
 */
object GovernanceHelper extends GovernanceHelper

/**
 * 异常体系，调用错误异常
 */
case class MSOACallException(message: String) extends RuntimeException(message)

/**
 * 寻找endpoint错误
 */
case class EndpointException(message: String) extends RuntimeException(message)

/**
 * 寻找拓扑错误
 * @param message
 */
case class ServiceTopologyException(message: String) extends RuntimeException(message)

/**
 * 一些常量
 *
 * @author Zhang Xu
 */
trait Constans {

  /** HTTP调用超时时间 */
  val READ_TIMEOUT_IN_SECONDS = 30

  /** HTTP调用连接时间 */
  val CONNECT_TIMEOUT_IN_MILLISECONDS = 5000

}

/**
 * URL定义的特质
 *
 * @author Zhang Xu
 */
trait UrlDefine {

  /** 服务治理中心URL */
  val ENDPOINT_DISCOVERY_URL_PREFIX = "service-governance/registry/discover"

  /** 微服务URL */
  val MICRO_SERVICE_URL_PREFIX = "navi2/rest"

  /**
   * 构建访问服务治理中心的URL
   *
   * @example http://10.57.213.15:8156/service-governance/registry/discover/101001/mediaService/2016032300/getMediaByAdUnitId
   *
   * @param address 服务治理中心地址
   * @param bundle 微服务接口上的注解BundleService，包括provider、service和version
   * @param methodName 调用的方法名
   * @return URL
   */
  def toEndPointDiscoveryUrl(address: String, bundle: BundleService, methodName: String): String = {
    "http://%s/%s%s%s".format(address, ENDPOINT_DISCOVERY_URL_PREFIX, toSuffix(bundle), methodName)
  }

  /**
   * 构建访问微服务的URL
   *
   * @example http://10.95.106.126:8801/navi2/rest/101001/mediaService/2016032300/getMediaByAdUnitId
   *
   * @param address 微服务地址
   * @param bundle 微服务Stub SDK接口上的注解BundleService，包括provider、service和version
   * @param methodName 调用的方法名
   * @return URL
   */
  def toServiceUrl(address: String, bundle: BundleService, methodName: String): String = {
    "http://%s/%s%s%s".format(address, MICRO_SERVICE_URL_PREFIX, toSuffix(bundle), methodName)
  }

  /**
   * 根据微服务打出的SDK包中接口的BundleService注解构造URL的路径
   *
   * @param bundle 微服务Stub SDK接口上的注解BundleService
   * @return URL部分路径
   */
  def toSuffix(bundle: BundleService) = Array(bundle.provider(), bundle.service(), bundle.version()).mkString("/", "/", "/")

}

////////////////////////////////////////
//   Dispatch 框架相关的自定义类
////////////////////////////////////////

object Bytes extends (client.Response => Array[Byte]) {
  /** @return response body as a string decoded as either the charset provided by
    *         Content-Type header of the response or ISO-8859-1 */
  def apply(r: client.Response) = {
    r.getResponseBodyAsBytes
  }

  /** @return a function that will return response body decoded in the provided charset */
  case class charset(set: Charset) extends (client.Response => String) {
    def apply(r: client.Response) = r.getResponseBody(set.name)
  }

  /** @return a function that will return response body as a utf8 decoded string */
  object utf8 extends charset(Charset.forName("utf8"))

}

case class ResponseWithSC(statusCode: Int, bytes: Array[Byte])

object BytesWithSC extends (client.Response => ResponseWithSC) {
  /** @return response body as a string decoded as either the charset provided by
    *         Content-Type header of the response or ISO-8859-1 */
  def apply(r: client.Response) = {
    ResponseWithSC(r.getStatusCode, r.getResponseBodyAsBytes)
  }

  /** @return a function that will return response body decoded in the provided charset */
  case class charset(set: Charset) extends (client.Response => String) {
    def apply(r: client.Response) = r.getResponseBody(set.name)
  }

  /** @return a function that will return response body as a utf8 decoded string */
  object utf8 extends charset(Charset.forName("utf8"))

}

case class MSOARequestHandlerTupleBuilder(req: Req) {
  def build[T](f: Response => T) =
    (req.toRequest, new MSOAFunctionHandler(f))
}

class MSOAFunctionHandler[T](f: Response => T) extends AsyncCompletionHandler[T] {
  def onCompleted(response: Response) = {
    //    val buf = response.getResponseBodyAsBytes
    //    val exp = new String(buf)
    //    println(response.getResponseBody)
    //    println(response.getStatusText)
    f(response)
  }
}

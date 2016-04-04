name := "scala-dsl-rpc-client"

version := "1.0"

scalaVersion := "2.11.7"

resolvers += "Sonatype OSS Snapshots" at "http://maven.scm.baidu.com:8081/nexus/content/groups/public"

libraryDependencies ++= Seq(
  //"com.typesafe.akka" %% "akka-remote" % "2.3.6",
  //"net.databinder" %% "dispatch-http" % "0.8.5",
  //"net.databinder" %% "dispatch-lift-json" % "0.8.5",
  //"org.scalatest" % "scalatest_2.11" % "2.2.6" % "test",
  "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
  "com.baidu.ub.msoa.stub" % "101001.mediaService" % "2016032300-SNAPSHOT",
  "com.baidu.ub.msoa.stub" % "101006.siteIndustryService" % "2015122400-SNAPSHOT",
  "com.baidu.ub.msoa" % "service-container" % "20160316-SNAPSHOT",
  "com.typesafe" % "config" % "1.2.1"
)
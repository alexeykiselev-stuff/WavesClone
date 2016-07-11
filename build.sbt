import com.typesafe.config.ConfigFactory

organization := "org.consensusresearch"

val appConf = ConfigFactory.parseFile(new File("src/main/resources/application.conf")).resolve().getConfig("app")

name := "waves"

version := appConf.getString("version")

scalaVersion := "2.11.8"

resolvers += "SonaType" at "https://oss.sonatype.org/content/groups/public"

val modulesVersion = "1.2.8-LOCAL"

libraryDependencies ++= Seq(
  "com.opencsv" % "opencsv" % "3.7",
  "com.github.scopt" %% "scopt" % "3.4.+",
  "org.consensusresearch" %% "scorex-basics" % modulesVersion,
  "org.consensusresearch" %% "scorex-consensus" % modulesVersion,
  "org.consensusresearch" %% "scorex-transaction" % modulesVersion,
  "io.spray" %% "spray-testkit" % "1.+" % "test",
  "org.scalatest" %% "scalatest" % "2.+" % "test",
  "org.scalactic" %% "scalactic" % "2.+" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.+" % "test",
  "net.databinder.dispatch" %% "dispatch-core" % "+" % "test"
)


//assembly settings
assemblyJarName in assembly := "rebuild.jar"

test in assembly := {}

mainClass in assembly := Some("scorex.transaction.state.database.blockchain.Rebuild")

assemblyMergeStrategy in assembly := {
  case "application.conf" => MergeStrategy.concat
  case "logback.xml" => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

wartremoverWarnings ++= Warts.all

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.13.0")

addSbtPlugin("com.github.tkawachi" % "sbt-lock" % "0.2.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.6")

addSbtPlugin("org.brianmckenna" % "sbt-wartremover" % "0.14")

libraryDependencies += "com.typesafe" % "config" % "1.3.0"
organization in ThisBuild := "io.cloudstate"
name := "cloudstate"
scalaVersion in ThisBuild := "2.12.8"

version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))

// Needed for our fork of skuber
resolvers in ThisBuild += Resolver.bintrayRepo("jroper", "maven")   // TODO: Remove once skuber has the required functionality
resolvers in ThisBuild += Resolver.bintrayRepo("akka", "snapshots") // TODO: Remove once Akka Http 10.1.9 is out

organizationName in ThisBuild := "Lightbend Inc."
startYear in ThisBuild := Some(2019)
licenses in ThisBuild += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

val AkkaVersion = "2.5.23-jroper-rebalance-fix-1"
val AkkaHttpVersion = "10.1.8+47-9ef9d823"
val AkkaManagementVersion = "1.0.1"
val AkkaPersistenceCassandraVersion = "0.96"
val PrometheusClientVersion = "0.6.0"
val ScalaTestVersion = "3.0.5"
val ProtobufVersion = "3.5.1"

def common: Seq[Setting[_]] = Seq(
  headerMappings := headerMappings.value ++ Seq(
    de.heikoseeberger.sbtheader.FileType("proto") -> HeaderCommentStyle.cppStyleLineComment,
    de.heikoseeberger.sbtheader.FileType("js") -> HeaderCommentStyle.cStyleBlockComment
  ),

  // Akka gRPC adds all protobuf files from the classpath to this, which we don't want because it includes
  // all the Google protobuf files which are already compiled and on the classpath by ScalaPB. So we set it
  // back to just our source directory.
  PB.protoSources in Compile := Seq(),
  PB.protoSources in Test := Seq(),

  excludeFilter in headerResources := HiddenFileFilter || GlobFilter("reflection.proto")
)

// Include sources from the npm projects
headerSources in Compile ++= {
  val nodeSupport = baseDirectory.value / "node-support"
  val jsShoppingCart = baseDirectory.value / "samples" / "js-shopping-cart"

  Seq(
    nodeSupport / "src" ** "*.js",
    nodeSupport * "*.js",
    jsShoppingCart * "*.js",
    jsShoppingCart / "test" ** "*.js"
  ).flatMap(_.get)
}

lazy val root = (project in file("."))
  .aggregate(`proxy-core`, `proxy-cassandra`, `akka-client`, operator, `tck`)
  .settings(common)

def dockerSettings: Seq[Setting[_]] = Seq(
  dockerBaseImage := "adoptopenjdk/openjdk8",
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry").orElse(Some("lightbend-docker-registry.bintray.io")),
  dockerUsername := sys.props.get("docker.username").orElse(Some("octo"))
)

lazy val `proxy-core` = (project in file("proxy/core"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, AkkaGrpcPlugin, JavaAgent, AssemblyPlugin)
  .settings(
    common,
    name := "cloudstate-proxy-core",
    libraryDependencies ++= Seq(
      "com.typesafe.akka"             %% "akka-persistence"                  % AkkaVersion,
      "com.typesafe.akka"             %% "akka-persistence-query"            % AkkaVersion,
      "com.typesafe.akka"             %% "akka-stream"                       % AkkaVersion,
      "com.typesafe.akka"             %% "akka-slf4j"                        % AkkaVersion,
      "com.typesafe.akka"             %% "akka-http"                         % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http-spray-json"              % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http-core"                    % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http2-support"                % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-cluster-sharding"             % AkkaVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
      "com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagementVersion,
      "com.google.protobuf"            % "protobuf-java"                     % ProtobufVersion % "protobuf",
      "com.google.protobuf"            % "protobuf-java-util"                % ProtobufVersion,

      "org.scalatest"                 %% "scalatest"                         % ScalaTestVersion % Test,
      "com.typesafe.akka"             %% "akka-testkit"                      % AkkaVersion % Test,
      "com.typesafe.akka"             %% "akka-stream-testkit"               % AkkaVersion % Test,
      "com.typesafe.akka"             %% "akka-http-testkit"                 % AkkaHttpVersion % Test,
      "com.thesamet.scalapb"          %% "scalapb-runtime"                   % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "io.prometheus"                  % "simpleclient"                      % PrometheusClientVersion,
      "io.prometheus"                  % "simpleclient_common"               % PrometheusClientVersion,
      "ch.qos.logback"                 % "logback-classic"                   % "1.2.3",
    ),

    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "proxy", baseDir / "frontend", (sourceDirectory in Compile).value / "protos")
    },

    // This adds the test/protos dir and enables the ProtocPlugin to generate protos in the Test scope
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources ++= Seq(sourceDirectory.value / "protos"),
        akkaGrpcGeneratedSources := Seq(AkkaGrpc.Server),
      )
    ),

    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.9" % "runtime;test",

    dockerSettings,

    fork in run := true,

    // In memory journal by default
    javaOptions in run ++= Seq("-Dconfig.resource=in-memory.conf", "-Dcloudstate.proxy.dev-mode-enabled=true"),

    mainClass in assembly := Some("io.cloudstate.proxy.CloudStateProxyMain"),

    assemblyJarName in assembly := "akka-proxy.jar",

    test in assembly := {},

    // logLevel in assembly := Level.Debug,

    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

lazy val `proxy-cassandra` = (project in file("proxy/cassandra"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, JavaAgent)
  .dependsOn(`proxy-core`)
  .settings(
    common,
    name := "cloudstate-proxy-cassandra",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-persistence-cassandra"          % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion % Test
    ),
    dockerSettings,

    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain")
  )

val compileK8sDescriptors = taskKey[File]("Compile the K8s descriptors into one")

lazy val operator = (project in file("operator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    common,
    name := "cloudstate-operator",
    // This is a publishLocal build of this PR https://github.com/doriordan/skuber/pull/268
    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-stream"     % AkkaVersion,
      "com.typesafe.akka"  %% "akka-slf4j"      % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"       % AkkaHttpVersion,
      "io.skuber"          %% "skuber"          % "2.2.0-jroper-1",
      "ch.qos.logback"      % "logback-classic" % "1.2.3"

    ),

    dockerSettings,
    dockerExposedPorts := Nil,
    compileK8sDescriptors := doCompileK8sDescriptors(
      baseDirectory.value / "deploy",
      baseDirectory.value / "cloudstate.yaml",
      dockerRepository.value.get,
      dockerUsername.value.get,
      version.value
    )
  )

lazy val `akka-client` = (project in file("samples/akka-js-shopping-cart-client"))
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    common,
    name := "akka-js-shopping-cart-client",

    fork in run := true,

    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-persistence"     % AkkaVersion,
      "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http-core"       % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http2-support"   % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-parsing"         % AkkaVersion,
      "com.google.protobuf" % "protobuf-java"        % ProtobufVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime"    % scalapb.compiler.Version.scalapbVersion % "protobuf"
    ),

    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    },
  )

lazy val `load-generator` = (project in file("samples/js-shopping-cart-load-generator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(`akka-client`)
  .settings(
    common,
    name := "js-shopping-cart-load-generator",
    dockerSettings,
    dockerExposedPorts := Nil
  )

val copyProtocolProtosToTCK = taskKey[File]("Copy the protocol files to the tck")

lazy val `tck` = (project in file("tck"))
  .enablePlugins(AkkaGrpcPlugin)
  .dependsOn(`akka-client`)
  .settings(
    common,

    name := "tck",

    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.google.protobuf" % "protobuf-java"        % ProtobufVersion % "protobuf",
      "org.scalatest"      %% "scalatest"            % ScalaTestVersion,
      "com.typesafe.akka"  %% "akka-testkit"         % AkkaVersion
    ),

    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "proxy")
    },

    fork in test := false,

    parallelExecution in Test := false,

    executeTests in Test := (executeTests in Test).dependsOn(`proxy-core`/assembly).value
  )

def doCompileK8sDescriptors(dir: File, target: File, registry: String, username: String, version: String): File = {
  val files = ((dir / "crds") * "*.yaml").get ++
    (dir * "*.yaml").get.sortBy(_.getName)

  val fullDescriptor = files.map(IO.read(_)).mkString("\n---\n")

  val substitutedDescriptor = List("cloudstate", "cloudstate-proxy-cassandra")
    .foldLeft(fullDescriptor) { (descriptor, image) =>
      descriptor.replace(s"lightbend-docker-registry.bintray.io/octo/$image:latest",
        s"$registry/$username/$image:$version")
    }

  IO.write(target, substitutedDescriptor)
  target
}

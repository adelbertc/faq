import sbt.complete.DefaultParsers.Space
import sbt.complete.Parser

lazy val buildSettings = List(
  organization := "com.adelbertc",
  licenses     += ("CC BY 4.0", url("https://creativecommons.org/licenses/by/4.0/")),
  scalaVersion := "2.11.8"
)

val catsVersion         = "0.7.2"
val disabledReplOptions = Set("-Ywarn-unused-import")

lazy val commonSettings = List(
  scalacOptions ++= List(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked",
    "-Xfatal-warnings",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused-import",
    "-Ywarn-value-discard"
  ),
  scalacOptions in (Compile, console) ~= { _.filterNot(disabledReplOptions.contains(_)) },
  scalacOptions in (Test, console) <<= (scalacOptions in (Compile, console)),
  libraryDependencies ++= List(
    compilerPlugin("org.spire-math" % "kind-projector" % "0.9.0" cross CrossVersion.binary),
    "org.typelevel" %% "cats-kernel" % catsVersion,
    "org.typelevel" %% "cats-core"   % catsVersion,
    "org.typelevel" %% "cats-free"   % catsVersion
  )
)

lazy val faqSettings = buildSettings ++ commonSettings

lazy val faq =
  project.in(file(".")).
  settings(name := "faq").
  settings(description := "").
  settings(faqSettings).
  settings(tutSettings)

// Taken from http://stackoverflow.com/questions/4730866/scala-expression-to-replace-a-file-extension-in-a-string
def replaceExtension(file: File): String =
  file.getName.replaceAll("\\.[^.]*$", "") + ".compiled.md"

def tutWithPath(file: File): Def.Initialize[Task[File]] = Def.taskDyn {
  val filename = file.getName
  tutOnly.toTask(s" ${filename}").map(_ => tutTargetDirectory.value / filename)
}

def renameTutFile(file: File, targetDirectory: File): File = {
  val newName = targetDirectory / replaceExtension(file)
  file.renameTo(newName)
  newName
}

def tutAndCopy(file: File): Def.Initialize[Task[File]] =
  tutWithPath(file)(_.map { tutFile =>
    renameTutFile(tutFile, file.getParentFile)
  })

val parser: Def.Initialize[State => Parser[File]] = Def.setting { (state: State) =>
  Space ~> tutFiles.value(state)
}

val tutGo: InputKey[Unit] = inputKey[Unit]("")
tutGo := Def.inputTaskDyn {
  val file = parser.parsed
  tutAndCopy(file)
}.evaluated

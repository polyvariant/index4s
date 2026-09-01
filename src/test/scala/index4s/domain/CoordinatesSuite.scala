package index4s.domain

import weaver.FunSuite

object CoordinatesSuite extends FunSuite {

  test(":: shorthand appends the scala binary suffix") {
    expect.eql(
      Coordinates.normalize("io.circe::circe-core"),
      Right("io.circe:circe-core_3")
    )
  }

  test(":: with version and latest keyword") {
    expect.all(
      Coordinates.normalize("io.circe::circe-core:0.14.16") ==
        Right("io.circe:circe-core_3:0.14.16"),
      Coordinates.normalize("io.circe::circe-core:latest") ==
        Right("io.circe:circe-core_3:latest")
    )
  }

  test("already-suffixed artifactId is respected, not double-suffixed") {
    expect.all(
      Coordinates.normalize("io.circe::circe-core_3") == Right(
        "io.circe:circe-core_3"
      ),
      Coordinates.normalize("io.circe::circe-core_3.3.8") == Right(
        "io.circe:circe-core_3.3.8"
      ),
      Coordinates.normalize("io.circe::circe-core_2.13") == Right(
        "io.circe:circe-core_2.13"
      )
    )
  }

  test("single-colon forms pass through verbatim (no suffix appended)") {
    expect.all(
      Coordinates.normalize("io.circe:circe-core") == Right(
        "io.circe:circe-core"
      ),
      Coordinates.normalize("io.circe:circe-core_3:0.14.16") == Right(
        "io.circe:circe-core_3:0.14.16"
      ),
      Coordinates.normalize("io.circe:circe-core:latest") == Right(
        "io.circe:circe-core:latest"
      )
    )
  }

  test("custom scala binary version") {
    expect.eql(
      Coordinates.normalize("io.circe::circe-core", "2.13"),
      Right("io.circe:circe-core_2.13")
    )
  }

  test("invalid inputs are Left, never exceptions") {
    val bad = List(
      "",
      "circe",
      ":a",
      "g:",
      "g::",
      "g::a:",
      "::a",
      "a:b:c:d",
      "g::a:1:2",
      "g:::a"
    )
    forEach(bad) { input =>
      expect(Coordinates.normalize(input).isLeft)
    }
  }
}

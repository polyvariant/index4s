package index4s.domain

import weaver.FunSuite

object DefaultArtifactSuite extends FunSuite {

  private def ref(artifactId: String, version: String = "1.0.0") =
    ArtifactRef("g.example", artifactId, version)

  test("plain _3 preferred over platform variants") {
    val refs = List(
      ref("circe-core_sjs1_3"),
      ref("circe-core_2.12"),
      ref("circe-core_3"),
      ref("circe-core_native0.5_3")
    )
    expect.eql(
      DefaultArtifact.select(refs).map(_.artifactId),
      Some("circe-core_3")
    )
  }

  test(
    "never platform-suffixed when plain exists; ties break lexicographically"
  ) {
    val refs = List(ref("circe_3"), ref("circe-core_3"), ref("circe_sjs1_3"))
    expect.eql(
      DefaultArtifact.select(refs).map(_.artifactId),
      Some("circe-core_3")
    )
  }

  test("tethys — no plain tethys_3 exists, tethys-cats_3 wins (live-proven)") {
    val refs = upickle.default.read[List[ArtifactRef]](
      Fixture.text("latest-tethys.json")
    )
    expect(refs.exists(_.artifactId == "tethys_3") == false) &&
    expect.eql(
      DefaultArtifact.select(refs).map(_.artifactId),
      Some("tethys-cats_3")
    )
  }

  test("requested scala version steers the plain pick") {
    val refs = List(ref("a_3"), ref("a_2.13"), ref("a_sjs1_2.13"))
    expect.eql(
      DefaultArtifact.select(refs, Some("2.13")).map(_.artifactId),
      Some("a_2.13")
    )
  }

  test("no scala preference: any plain ref wins") {
    val refs = List(ref("b_2.13"), ref("a_3"))
    expect.eql(
      DefaultArtifact.select(refs, None).map(_.artifactId),
      Some("a_3")
    )
  }

  test("no plain refs: stable pick, lexicographic tie-break") {
    val refs =
      List(ref("b_native0.5_3"), ref("a_sjs1_3"), ref("c_sjs1_3", "2.0.0-RC1"))
    expect.eql(DefaultArtifact.select(refs).map(_.artifactId), Some("a_sjs1_3"))
  }

  test("no plain refs, unstable only: any ref is returned") {
    val refs =
      List(ref("b_native0.5_3", "1.0.0-M2"), ref("a_sjs1_3", "0.1.0-SNAPSHOT"))
    expect.eql(DefaultArtifact.select(refs).map(_.artifactId), Some("a_sjs1_3"))
  }

  test("plain wins even if its version is a pre-release") {
    val refs = List(ref("a_3", "1.0.0-RC1"), ref("b_sjs1_3", "1.0.0"))
    expect.eql(DefaultArtifact.select(refs).map(_.artifactId), Some("a_3"))
  }

  test("empty list → None") {
    expect.eql(DefaultArtifact.select(Nil), None)
  }

  test("stability classification") {
    expect.all(
      DefaultArtifact.isStable("0.14.16"),
      DefaultArtifact.isStable("1.0.0"),
      !DefaultArtifact.isStable("0.14.16-RC1"),
      !DefaultArtifact.isStable("1.0.0-M5"),
      !DefaultArtifact.isStable("0.1.0-SNAPSHOT")
    )
  }
}

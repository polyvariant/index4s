package index4s.domain

import weaver.FunSuite

object PagingSuite extends FunSuite {

  test("pagesNeeded — limit 25 over pages of 20 ⇒ 2 pages") {
    expect.all(
      Paging.pagesNeeded(25, 20) == 2,
      Paging.pagesNeeded(20, 20) == 1,
      Paging.pagesNeeded(1, 20) == 1,
      Paging.pagesNeeded(41, 20) == 3,
      Paging.pagesNeeded(25) == 2
    )
  }

  test("limit greater than results ⇒ 1 page") {
    expect.all(
      Paging.pagesNeeded(7, 20) == 1,
      Paging.cap(20, 7) == 7
    )
  }

  test("cap = min(limit, fetched)") {
    expect.all(
      Paging.cap(25, 25) == 25,
      Paging.cap(25, 40) == 25,
      Paging.cap(25, 18) == 18
    )
  }

  test("degenerate inputs stay total") {
    expect.all(
      Paging.pagesNeeded(0) == 0,
      Paging.pagesNeeded(-5) == 0,
      Paging.pagesNeeded(10, 0) == 10,
      Paging.cap(0, 10) == 0,
      Paging.cap(-1, 5) == 0
    )
  }
}

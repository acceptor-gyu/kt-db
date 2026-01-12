package study.db.server

import java.util.concurrent.Executors

class DbServer {
    // DB connection leak 예외 확인을 위해 작은 수로 설정
    val executor = Executors.newFixedThreadPool(3)
}
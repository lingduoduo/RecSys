# RecSys


```
mvn clean compile
mvn exec:java -Dexec.mainClass="com.example.RecSysServer"
```

```
(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   main ±  curl -i http://localhost:6010/getmovie

HTTP/1.1 200 OK
Date: Thu, 22 Jan 2026 19:53:25 GMT
Content-Type: application/json
Content-Length: 34
Server: Jetty(11.0.18)

{"movie":"Inception","year":2010}
(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   main ±  curl -i "http://localhost:6010/getuser"
HTTP/1.1 200 OK
Date: Thu, 22 Jan 2026 19:55:32 GMT
Content-Type: application/json
Content-Length: 32
Server: Jetty(11.0.18)

{"userId":"123","name":"Alice"}
```
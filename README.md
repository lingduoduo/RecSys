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

(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   initial-setup ±  curl "http://localhost:6010/getrecommendation"
{ "userId": "123", "recommendations": ["Inception", "Interstellar", "The Dark Knight"] }

(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   initial-setup ±  curl -i "http://localhost:6010/getsimilarmovie"
HTTP/1.1 200 OK
Date: Thu, 22 Jan 2026 20:21:37 GMT
Content-Type: application/json
Content-Length: 68
Server: Jetty(11.0.18)

{ "movieId": "1", "similar": ["Interstellar", "Tenet", "Memento"] }
(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   initial-setup ±  curl -i "http://localhost:6010/getsimilarmovie?movieId=42"
HTTP/1.1 200 OK
Date: Thu, 22 Jan 2026 20:21:43 GMT
Content-Type: application/json
Content-Length: 69
Server: Jetty(11.0.18)

{ "movieId": "42", "similar": ["Interstellar", "Tenet", "Memento"] }

(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   main ±  curl "http://localhost:6010/getrecommendation?userId=42"
{ "userId": "42", "recommendations": ["Inception", "Interstellar"] }
(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   main ±  curl "http://localhost:6010/getrecommendation?userId=42&type=home&k=5"

{ "userId": "42", "recommendations": ["Inception", "Interstellar"] }
(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   main ±  curl "http://localhost:6010/getrecommendation?userId=42&type=similar&movieId=99&k=3"
{ "userId": "42", "recommendations": ["Inception", "Interstellar"] }
```

### Redis

```
docker exec -it redis-dev redis-cli SET i2vEmb:1 "1 0 0"
docker exec -it redis-dev redis-cli SET i2vEmb:2 "0.9 0.1 0"
docker exec -it redis-dev redis-cli SET i2vEmb:3 "0 1 0"
```
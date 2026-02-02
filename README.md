# RecSys

## Feature Encoder and engineering

- Directly leverage LLM embeddings
  - GPT embedding is already commonly used in retrieval tasks, Scaling laws on embedding performance vs. model size
  - LLM derived features based on eCommerce item textual data
    
- LLM augmented features
  - Encoded into text embeddings
  - Categorized as sparse features
  
## LLM used as a ranker/re-ranker

- Off the shelf LLM as a recommender with prompt engineering
  - Prompting is needed, and tuning is mainly focusing on the prompts
    
- Fine-tuned LLM
  - Supervised Fine Tuning (SFT), Directly generate the target item to recommend
  - Instruction Tuning (Human Alignment)
    
- LLM as a reranker
  - LLM can be used to aim for specific goals - diverse reranking, relevance, freshness
    - Enhancing Recommendation Diversity by Re-ranking with Large Language Models
    - Large Language Models are Zero-Shot Rankers for Recommender Systems
      - struggle to perceive the order of historical interactions, and
      - can be biased by popularity or item positions in the prompts
      - van be overcome by careful prompt engineering
  - LLM as a domain specialist helping recommendation: e.g., Health, Finance, Medical Care, Law

## LLM for user-interaction

- Conversational Recommendation
  
## LM inspired architecture

- Generative Sequence Training / modeling
- New Transformer Arch: HSTU
- MoE

### Redis

```
docker exec -it redis-dev redis-cli SET i2vEmb:1 "1 0 0"
docker exec -it redis-dev redis-cli SET i2vEmb:2 "0.9 0.1 0"
docker exec -it redis-dev redis-cli SET i2vEmb:3 "0 1 0"
```

### Test Runs
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


(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   similar-items ±  curl -i -X POST "http://localhost:6010/setembedding?movieId=4" \
  -H "Content-Type: text/plain" \
  --data-binary "0.2 0.2 0.6"

HTTP/1.1 200 OK
Date: Thu, 22 Jan 2026 23:56:05 GMT
Content-Type: application/json;charset=utf-8
Access-Control-Allow-Origin: *
Content-Length: 32
Server: Jetty(11.0.18)

{"ok":true,"movieId":4,"dim":3}
(base)  🐍 base  linghuang@Mac  ~/Git/RecSys/recsys-api   similar-items ±  curl -i -X POST "http://localhost:6010/setembedding?movieId=5" \
  --data-urlencode "vec=0.1 0.3 0.6"
HTTP/1.1 200 OK
Date: Thu, 22 Jan 2026 23:56:13 GMT
Content-Type: application/json;charset=utf-8
Access-Control-Allow-Origin: *
Content-Length: 32
Server: Jetty(11.0.18)

{"ok":true,"movieId":5,"dim":3}

```


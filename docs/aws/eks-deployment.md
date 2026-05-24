# AWS EKS Deployment

This repo uses Kubernetes-native service discovery on EKS: each service gets a stable
`ClusterIP` service name, and the gateway routes to those DNS names through env vars
from `recsys-config`.

## Build And Push To ECR

```bash
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
IMAGE="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/recsys-backend-service:latest"

aws ecr create-repository \
  --repository-name recsys-backend-service \
  --region "${AWS_REGION}" || true

aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin \
    "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

docker build -t "${IMAGE}" .
docker push "${IMAGE}"
```

## Deploy To EKS

Edit `k8s/eks/kustomization.yaml` and set `newName` / `newTag` to your ECR image.

```bash
aws eks update-kubeconfig --region "${AWS_REGION}" --name <cluster-name>
kubectl apply -k k8s/eks
kubectl -n recsys rollout status deployment/recsys-api-gateway
kubectl -n recsys get svc recsys-api-gateway
```

The gateway service is `type: LoadBalancer` and includes AWS NLB annotations. For
production, replace the demo Redis Deployment with Amazon ElastiCache or another
managed Redis endpoint by changing `REDIS_HOST` in `k8s/base/configmap.yaml`.
For public ingress throttling and shared gateway limits, see
[rate-limiting-investigation.md](rate-limiting-investigation.md).

## Service Discovery

No app-level registry is required on Kubernetes. The gateway uses these internal
DNS names from the ConfigMap:

```text
http://recsys-catalog-serving:6010
http://recsys-model-serving:8080
http://recsys-online-serving:7010
```

Kubernetes DNS resolves each name to the healthy pods selected by its Service.

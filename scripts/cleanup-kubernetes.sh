echo "Cleaning up current Kubernetes resources"

echo ""

echo "Cleaning up current Deployments"
kubectl delete deployment --all

echo ""

echo "Cleaning up current Pods"
kubectl delete po --all

echo ""

echo "Cleaning up current Ingresses"
kubectl delete ingress --all

echo ""

echo "Cleaning up current ConfigMaps"
kubectl delete configmap --all

echo ""

echo "Cleaning up current Services"
kubectl delete svc quote-generator micro-trader-dashboard portfolio-service compulsive-traders audit-database audit-service

echo ""

echo "Cleaning up current Secrets"
kubectl delete secret audit-database-config

echo ""

echo "Cleaning up current Persistence Volume"
kubectl patch pv postgres-pv-volume -p '{"metadata":{"finalizers": []}}' --type=merge
kubectl delete pv postgres-pv-volume

echo ""

echo "Cleaning up current Persistence Volume Claim"
kubectl patch pvc postgres-pvc-claim -p '{"metadata":{"finalizers": []}}' --type=merge
kubectl delete pvc postgres-pv-claim

echo ""

echo "Well done!"
#!/usr/bin/env bash
echo "Cleaning up target folder into the modules"
echo ""
cd ../
mvn clean

echo ""

echo "Cleaning up fat JARs"

echo ""

echo "Cleaning up fat JAR into quote-generator"
cd quote-generator
rm -rf quote-generator-1.0-SNAPSHOT.jar
echo "Ok"

echo ""

echo "Cleaning up fat JAR into micro-trader-dashboard"
cd ../micro-trader-dashboard
rm -rf micro-trader-dashboard-1.0-SNAPSHOT.jar
echo "Ok"

echo ""

echo "Cleaning up fat JAR into portfolio-service"
cd ../portfolio-service
rm -rf portfolio-service-1.0-SNAPSHOT.jar
echo "Ok"

echo ""

echo "Cleaning up fat JAR into compulsive-traders"
cd ../compulsive-traders
rm -rf compulsive-traders-1.0-SNAPSHOT.jar
echo "Ok"

echo ""

echo "Cleaning up fat JAR into audit-service"
cd ../audit-service
rm -rf audit-service-1.0-SNAPSHOT.jar
echo "Ok"

echo ""

echo "Cleaning up current Kubernetes resources"

echo ""

echo "Cleaning up current Pods and Deployments"
kubectl delete deployment --all

echo ""

echo "Cleaning up current Ingresses"
kubectl delete ingress --all

echo ""

echo "Cleaning up current ConfigMaps"
kubectl delete configmap --all

echo ""

echo "Cleaning up current Services"
kubectl delete svc quote-generator
kubectl delete svc micro-trader-dashboard
kubectl delete svc portfolio-service
kubectl delete svc compulsive-traders
kubectl delete svc audit-database
kubectl delete svc audit-service

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
kubectl delete pvc postgres-pvc-claim

echo ""

echo "Deleting Docker images"

echo ""

docker rmi sidartasilva/quote-generator:latest
docker rmi sidartasilva/micro-trader-dashboard:latest
docker rmi sidartasilva/portfolio-service:latest
docker rmi sidartasilva/compulsive-traders:latest
docker rmi sidartasilva/audit-service:latest
docker rmi postgres:12.1-alpine

echo ""

docker images

echo ""

echo "Well done!"
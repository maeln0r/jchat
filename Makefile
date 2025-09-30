.PHONY: infra app down pf

REG?=jchat/identity
TAG?=dev

infra:
	# 0) секреты и ns
	kubectl apply -k deploy/k8s/
	# 1) Postgres для приложения
	helm upgrade --install pg oci://registry-1.docker.io/bitnamicharts/postgresql \
	  -n jchat --create-namespace \
	  -f deploy/k8s/values/values-pg.yaml \
	  --wait --timeout 10m
	# 2) Postgres для Keycloak
	helm upgrade --install kc-pg oci://registry-1.docker.io/bitnamicharts/postgresql \
	  -n jchat \
	  -f deploy/k8s/values/values-kc-pg.yaml \
	  --wait --timeout 10m
	# 3) Kafka
	helm upgrade --install kafka oci://registry-1.docker.io/bitnamicharts/kafka \
	  -n jchat \
	  -f deploy/k8s/values/values-kafka.yaml \
	  --wait --timeout 10m
	# 4) Keycloak (внешняя БД)
	helm upgrade --install keycloak oci://registry-1.docker.io/bitnamicharts/keycloak \
    	  -n jchat \
    	  -f deploy/k8s/values/values-keycloak.yaml \
    	  --wait --timeout 10m

app:
	# Локальная сборка образа
	docker build -t $(REG):$(TAG) -f services/identity-service/Dockerfile .
	helm upgrade --install identity-service deploy/k8s/charts/identity-service \
	  -n jchat \
	  --set image.repository=$(REG) \
	  --set image.tag=$(TAG) \
	  --wait --timeout 10m

pf:
	mkdir -p .pf
	# keycloak -> 8081
	-kill `cat .pf/keycloak.pid 2>/dev/null` 2>/dev/null || true
	nohup kubectl -n jchat port-forward svc/keycloak 8081:8080 >/dev/null 2>&1 & echo $$! > .pf/keycloak.pid
	# identity -> 8091
	-kill `cat .pf/identity.pid 2>/dev/null` 2>/dev/null || true
	nohup kubectl -n jchat port-forward svc/identity-service 8091:8091 >/dev/null 2>&1 & echo $$! > .pf/identity.pid
	@echo "Port-forwards started. Keycloak: 8081, Identity: 8091"

stop-pf:
	-@[ -f .pf/keycloak.pid ] && kill `cat .pf/keycloak.pid` 2>/dev/null || true
	-@[ -f .pf/identity.pid ] && kill `cat .pf/identity.pid` 2>/dev/null || true
	@rm -rf .pf || true
	@echo "Port-forwards stopped."

e2e: infra app pf

down: stop-pf
	helm -n jchat uninstall identity-service keycloak kafka kc-pg pg || true
	kubectl delete -k deploy/k8s/ || true

get-pods:
	kubectl -n jchat get pods

upgrade:
	helm upgrade --install identity-service deploy/k8s/charts/identity-service -n jchat \
      --set image.repository=jchat/identity --set image.tag=dev --wait --timeout 10m
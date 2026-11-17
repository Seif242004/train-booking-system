// Pipeline of the local CI/CD (see scripts/ci.mjs): test, build images, push to the local registry, deploy to the kind cluster, smoke test.
// Secrets come only from Jenkins credentials (loaded from the root .env at Jenkins start-up) and are masked in the console.
pipeline {
  agent any
  options {
    disableConcurrentBuilds()
    timeout(time: 60, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '15'))
  }
  parameters {
    booleanParam(name: 'RUN_DB_TESTS', defaultValue: true, description: 'Run the Oracle database tests when Oracle is reachable (else they are skipped)')
    booleanParam(name: 'RUN_SMOKE', defaultValue: true, description: 'Run scripts/smoke-test.mjs against the cluster Gateway after the deploy')
  }
  environment {
    REGISTRY = 'localhost:5001/tbs'
    NS = 'tbs'
    COMPONENTS = 'train-schedule booking-seat payment ticket notification gateway frontend'
    DOCKER_BUILDKIT = '1'
    GRADLE_OPTS = '-Xmx768m'
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script { env.TAG = sh(returnStdout: true, script: 'git rev-parse --short=8 HEAD').trim() }
        echo "Commit ${env.TAG}"
      }
    }

    stage('Build and test') {
      steps {
        // Spring services. The database tests need the *_test schemas: they run only when asked for AND Oracle answers on the Compose
        // network; otherwise the passwords are unset, which makes the tests skip themselves (Gradle prints each skip).
        withCredentials([
          string(credentialsId: 'BOOKING_SEAT_TEST_DB_PASSWORD', variable: 'BOOKING_SEAT_TEST_DB_PASSWORD'),
          string(credentialsId: 'PAYMENT_TEST_DB_PASSWORD', variable: 'PAYMENT_TEST_DB_PASSWORD'),
          string(credentialsId: 'TICKET_TEST_DB_PASSWORD', variable: 'TICKET_TEST_DB_PASSWORD'),
          string(credentialsId: 'NOTIFICATION_TEST_DB_PASSWORD', variable: 'NOTIFICATION_TEST_DB_PASSWORD')
        ]) {
          sh '''#!/bin/bash
            reason=""
            if [ "${RUN_DB_TESTS:-true}" != "true" ]; then reason="RUN_DB_TESTS is off"
            elif ! bash -c "exec 3<>/dev/tcp/oracle/1521" 2>/dev/null; then reason="Oracle is not reachable from Jenkins"
            fi
            if [ -z "$reason" ]; then
              echo "Oracle reachable: running the database tests"
              for s in BOOKING_SEAT PAYMENT TICKET NOTIFICATION; do export ${s}_TEST_DB_URL=jdbc:oracle:thin:@//oracle:1521/FREEPDB1; done
            else
              echo "DB TESTS SKIPPED: $reason"
              unset BOOKING_SEAT_TEST_DB_PASSWORD PAYMENT_TEST_DB_PASSWORD TICKET_TEST_DB_PASSWORD NOTIFICATION_TEST_DB_PASSWORD
            fi
            # cleanTest: a cached result must not hide whether the database tests really ran.
            bash ./gradlew --no-daemon --console=plain --continue cleanTest test
          '''
        }
        // Gateway and web app. `npm run build` of the web app runs tsc first.
        sh 'cd gateway && npm ci && npm test'
        sh 'cd frontend && npm ci && npm run build'
      }
    }

    stage('Build images') {
      steps {
        sh '''#!/bin/bash
          set -e
          # No .env or key file may be part of the repository, so none can reach an image (the root .dockerignore also excludes them).
          if git ls-files | grep -E '(^|/)\\.env$|\\.pem$|\\.key$'; then echo "secret file in the repository"; exit 1; fi
          for c in train-schedule booking-seat payment ticket notification; do
            docker build -f infra/docker/spring.Dockerfile --build-arg SERVICE=$c -t $REGISTRY/$c:$TAG -t $REGISTRY/$c:latest .
          done
          docker build -f infra/docker/gateway.Dockerfile -t $REGISTRY/gateway:$TAG -t $REGISTRY/gateway:latest .
          docker build -f infra/docker/frontend.Dockerfile -t $REGISTRY/frontend:$TAG -t $REGISTRY/frontend:latest .
        '''
      }
    }

    stage('Push') {
      steps {
        sh '''#!/bin/bash
          set -e
          for c in $COMPONENTS; do docker push $REGISTRY/$c:$TAG; docker push $REGISTRY/$c:latest; done
        '''
      }
    }

    stage('Deploy') {
      steps {
        withCredentials([
          string(credentialsId: 'INTERNAL_API_KEY', variable: 'INTERNAL_API_KEY'),
          string(credentialsId: 'TICKET_SIGNING_KEY', variable: 'TICKET_SIGNING_KEY'),
          string(credentialsId: 'TRAIN_SCHEDULE_DB_PASSWORD', variable: 'TRAIN_SCHEDULE_DB_PASSWORD'),
          string(credentialsId: 'BOOKING_SEAT_DB_PASSWORD', variable: 'BOOKING_SEAT_DB_PASSWORD'),
          string(credentialsId: 'PAYMENT_DB_PASSWORD', variable: 'PAYMENT_DB_PASSWORD'),
          string(credentialsId: 'TICKET_DB_PASSWORD', variable: 'TICKET_DB_PASSWORD'),
          string(credentialsId: 'NOTIFICATION_DB_PASSWORD', variable: 'NOTIFICATION_DB_PASSWORD'),
          string(credentialsId: 'STRIPE_SECRET_KEY', variable: 'STRIPE_SECRET_KEY'),
          string(credentialsId: 'STRIPE_PUBLISHABLE_KEY', variable: 'STRIPE_PUBLISHABLE_KEY'),
          string(credentialsId: 'STRIPE_WEBHOOK_SECRET', variable: 'STRIPE_WEBHOOK_SECRET'),
          string(credentialsId: 'OPENOBSERVE_USER', variable: 'OPENOBSERVE_USER'),
          string(credentialsId: 'OPENOBSERVE_PASSWORD', variable: 'OPENOBSERVE_PASSWORD'),
          string(credentialsId: 'MAIL_HOST', variable: 'MAIL_HOST'),
          string(credentialsId: 'MAIL_PORT', variable: 'MAIL_PORT'),
          string(credentialsId: 'MAIL_USERNAME', variable: 'MAIL_USERNAME'),
          string(credentialsId: 'MAIL_PASSWORD', variable: 'MAIL_PASSWORD'),
          string(credentialsId: 'MAIL_AUTH', variable: 'MAIL_AUTH'),
          string(credentialsId: 'MAIL_STARTTLS', variable: 'MAIL_STARTTLS')
        ]) {
          sh '''#!/bin/bash
            set +x
            set -e
            kubectl apply -f deploy/k8s/overlays/local/namespace.yaml
            # The Secret is built from an env file on a pipe, so no value appears in a command line or in the log. The mail values
            # default to Mailpit, like docker-compose.yml.
            kubectl -n $NS create secret generic tbs-secrets --dry-run=client -o yaml --from-env-file=<(
              printf '%s=%s\\n' \
                INTERNAL_API_KEY "$INTERNAL_API_KEY" TICKET_SIGNING_KEY "$TICKET_SIGNING_KEY" \
                TRAIN_SCHEDULE_DB_PASSWORD "$TRAIN_SCHEDULE_DB_PASSWORD" BOOKING_SEAT_DB_PASSWORD "$BOOKING_SEAT_DB_PASSWORD" \
                PAYMENT_DB_PASSWORD "$PAYMENT_DB_PASSWORD" TICKET_DB_PASSWORD "$TICKET_DB_PASSWORD" NOTIFICATION_DB_PASSWORD "$NOTIFICATION_DB_PASSWORD" \
                STRIPE_SECRET_KEY "$STRIPE_SECRET_KEY" STRIPE_PUBLISHABLE_KEY "$STRIPE_PUBLISHABLE_KEY" STRIPE_WEBHOOK_SECRET "$STRIPE_WEBHOOK_SECRET" \
                OPENOBSERVE_USER "$OPENOBSERVE_USER" OPENOBSERVE_PASSWORD "$OPENOBSERVE_PASSWORD" \
                MAIL_HOST "${MAIL_HOST:-mailpit}" MAIL_PORT "${MAIL_PORT:-1025}" MAIL_USERNAME "$MAIL_USERNAME" MAIL_PASSWORD "$MAIL_PASSWORD" \
                MAIL_AUTH "${MAIL_AUTH:-false}" MAIL_STARTTLS "${MAIL_STARTTLS:-false}"
            ) | kubectl apply -f - > /dev/null
            echo "secret tbs-secrets applied"
          '''
        }
        // Image tag substitution: the overlay says :latest, the rendered manifests get the commit's tag.
        sh '''#!/bin/bash
          set -e
          kubectl kustomize deploy/k8s/overlays/local | sed -E "s#(localhost:5001/tbs/[a-z-]+):latest#\\1:$TAG#" | kubectl apply -f -
          fail=0
          for c in $COMPONENTS; do
            if ! kubectl -n $NS rollout status deploy/$c --timeout=420s; then
              echo "ROLLOUT FAILED: $c"; kubectl -n $NS logs deploy/$c --tail=60 || true
              kubectl -n $NS rollout undo deploy/$c || true
              fail=1
            fi
          done
          [ $fail = 0 ]
        '''
      }
    }

    stage('Smoke test') {
      when { expression { params.RUN_SMOKE } }
      steps {
        // The cluster Gateway is reached on the kind node (NodePort); Keycloak, Mailpit and OpenObserve on the Compose network.
        withCredentials([
          string(credentialsId: 'STRIPE_SECRET_KEY', variable: 'STRIPE_SECRET_KEY'),
          string(credentialsId: 'KEYCLOAK_DEV_USER_PASSWORD', variable: 'KEYCLOAK_DEV_USER_PASSWORD'),
          string(credentialsId: 'KEYCLOAK_DEV_ADMIN_PASSWORD', variable: 'KEYCLOAK_DEV_ADMIN_PASSWORD'),
          string(credentialsId: 'OPENOBSERVE_USER', variable: 'OPENOBSERVE_USER'),
          string(credentialsId: 'OPENOBSERVE_PASSWORD', variable: 'OPENOBSERVE_PASSWORD')
        ]) {
          sh '''GATEWAY_URL=http://tbs-control-plane:30080 KEYCLOAK_URL=http://keycloak:8080 \
                MAILPIT_URL=http://mailpit:8025/api/v1 OPENOBSERVE_URL=http://openobserve:5080/api/default \
                node scripts/smoke-test.mjs'''
        }
      }
    }
  }
  post {
    always {
      sh '''kubectl -n $NS get pods -o wide || true
            echo "Deployed images:"; kubectl -n $NS get deploy -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.template.spec.containers[0].image}{"\\n"}{end}' || true'''
    }
  }
}

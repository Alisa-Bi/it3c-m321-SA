#!/bin/bash
set -e

KEYCLOAK_URL="http://keycloak:8080"
ADMIN_USER="admin"
ADMIN_PASSWORD="admin"

REALM="chatapp"
CLIENT_ID="chat-service"
CLIENT_SECRET="chat-service-secret"

USERNAME="testuser"
PASSWORD="test123"

echo "Warte auf Keycloak..."

until /opt/keycloak/bin/kcadm.sh config credentials --server "$KEYCLOAK_URL" --realm master --user "$ADMIN_USER" --password "$ADMIN_PASSWORD" >/dev/null 2>&1
do
    sleep 2
done

echo "Keycloak ist erreichbar."

echo "Pruefe Realm $REALM..."

if ! /opt/keycloak/bin/kcadm.sh get "realms/$REALM" -r master >/dev/null 2>&1; then
    echo "Erstelle Realm $REALM..."

    /opt/keycloak/bin/kcadm.sh create realms \
        -r master \
        -s realm="$REALM" \
        -s enabled=true
else
    echo "Realm $REALM existiert bereits."
fi

echo "Deaktiviere unnoetige Required Actions..."

for ACTION in VERIFY_PROFILE UPDATE_PROFILE VERIFY_EMAIL CONFIGURE_TOTP UPDATE_PASSWORD; do
    /opt/keycloak/bin/kcadm.sh update "authentication/required-actions/$ACTION" \
        -r "$REALM" \
        -s enabled=false \
        -s defaultAction=false >/dev/null 2>&1 || true
done

echo "Pruefe Client $CLIENT_ID..."

CLIENT_EXISTS=$(
    /opt/keycloak/bin/kcadm.sh get clients \
        -r "$REALM" \
        -q clientId="$CLIENT_ID" \
        --fields id \
        --format csv \
        --noquotes 2>/dev/null || true
)

if [ -z "$CLIENT_EXISTS" ]; then
    echo "Erstelle Client $CLIENT_ID..."

    /opt/keycloak/bin/kcadm.sh create clients \
        -r "$REALM" \
        -s clientId="$CLIENT_ID" \
        -s enabled=true \
        -s protocol=openid-connect \
        -s publicClient=false \
        -s clientAuthenticatorType=client-secret \
        -s secret="$CLIENT_SECRET" \
        -s standardFlowEnabled=true \
        -s directAccessGrantsEnabled=true
else
    echo "Client $CLIENT_ID existiert bereits."
fi

echo "Pruefe User $USERNAME..."

USER_ID=$(
    /opt/keycloak/bin/kcadm.sh get users \
        -r "$REALM" \
        -q username="$USERNAME" \
        --fields id \
        --format csv \
        --noquotes 2>/dev/null | head -n 1 || true
)

if [ -z "$USER_ID" ]; then
    echo "Erstelle User $USERNAME..."

    /opt/keycloak/bin/kcadm.sh create users \
        -r "$REALM" \
        -s username="$USERNAME" \
        -s enabled=true

    USER_ID=$(
        /opt/keycloak/bin/kcadm.sh get users \
            -r "$REALM" \
            -q username="$USERNAME" \
            --fields id \
            --format csv \
            --noquotes | head -n 1
    )
else
    echo "User $USERNAME existiert bereits."
fi

echo "Entferne Required Actions vom User..."

 /opt/keycloak/bin/kcadm.sh update "users/$USER_ID" \
    -r "$REALM" \
    -s 'requiredActions=[]' \
    -s enabled=true

echo "Setze User-Passwort..."

/opt/keycloak/bin/kcadm.sh set-password \
    -r "$REALM" \
    --username "$USERNAME" \
    --new-password "$PASSWORD" \
    --temporary=false

echo ""
echo "======================================"
echo "Keycloak Initialisierung abgeschlossen"
echo "Realm:         $REALM"
echo "Client:        $CLIENT_ID"
echo "Client Secret: $CLIENT_SECRET"
echo "User:          $USERNAME"
echo "Passwort:      $PASSWORD"
echo "======================================"
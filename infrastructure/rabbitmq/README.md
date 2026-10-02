# RabbitMQ

Kein eigenes Definitionsfile noetig: Exchange, Queue und Binding werden vom
chat-service selbst als Spring-Beans angelegt (siehe
`backend/chat-service/.../messaging/RabbitMqConfig.java`). Das haelt die
Konfiguration an einer Stelle, naeher am Code, der sie tatsaechlich benutzt.

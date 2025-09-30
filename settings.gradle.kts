rootProject.name = "jchat"

include(
  ":services:gateway",
  ":services:chat-write",
  ":services:chat-delivery",
  ":services:presence",
  ":services:media",
  ":services:identity-service",
  ":services:notifications",
  ":libs:common-starter",
  ":libs:domain",
  ":libs:contracts"
)

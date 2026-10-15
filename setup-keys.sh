#!/bin/sh
set -eu
cd "$(dirname "$0")"

prop() {
    sed -n "s/^$1=//p" local.properties | sed "s/'/'\\\\''/g"
}

adb shell "am start -n dev.mushinlab.claudewatch/.KeySetupActivity \
  --es api_key '$(prop claude.apiKey)' \
  --es notion_token '$(prop notion.token)' \
  --es notion_prompt '$(prop notion.prompt)'"

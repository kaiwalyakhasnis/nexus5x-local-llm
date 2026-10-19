#!/bin/sh
# Send a GGUF model to the phone's RAM (/tmp/model.gguf); llmd frees the old one, then
# restarts the server on the new one (the phone takes the name from the GGUF itself).
# The Nexus 5X LLM app (manager/) does the same from a web page.
# Usage: scripts/send-model.sh model.gguf
#        scripts/send-model.sh qwen2.5:0.5b        (a model already pulled by Ollama on this PC)
set -e
P=$(cd "$(dirname "$0")/.." && pwd)
PHONE=${PHONE:-172.16.42.1}
F=$1
[ -n "$F" ] || { echo "usage: $0 model.gguf | ollama-name:tag" >&2; exit 1; }
if [ ! -f "$F" ]; then
	# Ollama name -> its GGUF blob (the manifest's "model" layer)
	name=${F%%:*} tag=${F#*:}; [ "$tag" = "$F" ] && tag=latest
	man=$HOME/.ollama/models/manifests/registry.ollama.ai/library/$name/$tag
	[ -f "$man" ] || { echo "no file or Ollama model: $F" >&2; exit 1; }
	dig=$(python3 -c 'import json,sys; print([l["digest"] for l in json.load(open(sys.argv[1]))["layers"] if l["mediaType"].endswith(".model")][0])' "$man")
	F=$HOME/.ollama/models/blobs/$(echo "$dig" | tr : -)
fi
[ "$(head -c 4 "$F")" = GGUF ] || { echo "$F is not a GGUF file" >&2; exit 1; }
echo "sending $(du -h "$F" | cut -f1) ..."
start=$(date +%s)
nc -N "$PHONE" 9000 < "$F"
echo "sent in $(( $(date +%s) - start )) s; waiting for the server"
for i in $(seq 1 60); do
	curl -sf "http://$PHONE:8080/health" >/dev/null 2>&1 && { echo "server ready: http://$PHONE:8080"; exit 0; }
	sleep 1
done
echo "server not up yet; check: scripts/phone.py 'tail /tmp/llama-server.log'"

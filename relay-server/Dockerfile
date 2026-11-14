FROM node:20-alpine

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY server.js ./

# Accounts, prekeys, queued envelopes and encrypted file chunks. Mount a persistent volume
# here or every restart forgets all users.
ENV DATA_FILE=/data/data.json \
    BLOB_DIR=/data/blobs \
    PORT=8080
RUN mkdir -p /data && chown node:node /data
VOLUME /data

USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
CMD ["node", "server.js"]

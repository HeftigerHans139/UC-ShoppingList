FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev --ignore-scripts

COPY src ./src
COPY public ./public
RUN mkdir -p /app/data

EXPOSE 8080

CMD ["node", "--max-old-space-size=96", "src/index.js"]

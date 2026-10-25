<template>
  <div class="video-page-card-small">
    <div class="card-box">
      <!-- 卡片左边——封面、时长 -->
      <div class="pic-box">
        <a :href="`/video/${item.videoId}`">
          <div class="pic">
            <img v-if="item.coverUrl" :src="mediaUrl(item.coverUrl)" alt="" @error="useFallbackImage" />
            <span class="duration">{{ handleDuration(item.duration) }}</span>
          </div></a
        >
      </div>
      <!-- 卡片右边——标题、up主用户名、播放数、弹幕数 -->
      <div class="info">
        <a :href="`/video/${item.videoId}`">
          <p class="title">{{ item.title }}</p></a
        >
        <a :href="`/user/${item.userId}`" target="_blank" class="upname">
          <svg
            t="1703614018039"
            class="icon"
            viewBox="0 0 1024 1024"
            version="1.1"
            xmlns="http://www.w3.org/2000/svg"
            p-id="4221"
            width="18"
            height="18"
          >
            <path
              d="M800 128H224C134.4 128 64 198.4 64 288v448c0 89.6 70.4 160 160 160h576c89.6 0 160-70.4 160-160V288c0-89.6-70.4-160-160-160z m96 608c0 54.4-41.6 96-96 96H224c-54.4 0-96-41.6-96-96V288c0-54.4 41.6-96 96-96h576c54.4 0 96 41.6 96 96v448z"
              p-id="4222"
            ></path>
            <path
              d="M419.2 544c0 51.2-3.2 108.8-83.2 108.8S252.8 595.2 252.8 544v-217.6H192v243.2c0 96 51.2 140.8 140.8 140.8 89.6 0 147.2-48 147.2-144v-240h-60.8V544zM710.4 326.4h-156.8V704h60.8v-147.2h96c102.4 0 121.6-67.2 121.6-115.2 0-44.8-19.2-115.2-121.6-115.2z m-3.2 179.2h-92.8V384h92.8c32 0 60.8 12.8 60.8 60.8 0 44.8-32 60.8-60.8 60.8z"
              p-id="4223"
            ></path>
          </svg>
          <span class="name">{{ item.nickName }}</span>
        </a>
        <div class="playinfo">
          <svg
            t="1703614610134"
            class="playinfo-play"
            viewBox="0 0 1024 1024"
            version="1.1"
            xmlns="http://www.w3.org/2000/svg"
            p-id="4864"
            width="18"
            height="18"
          >
            <path
              d="M800 128H224C134.4 128 64 198.4 64 288v448c0 89.6 70.4 160 160 160h576c89.6 0 160-70.4 160-160V288c0-89.6-70.4-160-160-160z m96 608c0 54.4-41.6 96-96 96H224c-54.4 0-96-41.6-96-96V288c0-54.4 41.6-96 96-96h576c54.4 0 96 41.6 96 96v448z"
              p-id="4865"
              fill="#aaaaaa"
            ></path>
            <path
              d="M684.8 483.2l-256-112c-22.4-9.6-44.8 6.4-44.8 28.8v224c0 22.4 22.4 38.4 44.8 28.8l256-112c25.6-9.6 25.6-48 0-57.6z"
              p-id="4866"
            ></path>
          </svg>
          {{ handleNum(item.viewCount) }}
          <svg
            t="1703614725224"
            class="playinfo-dm"
            viewBox="0 0 1024 1024"
            version="1.1"
            xmlns="http://www.w3.org/2000/svg"
            p-id="5601"
            id="mx_n_1703614725225"
            width="18"
            height="18"
          >
            <path
              d="M800 128H224C134.4 128 64 198.4 64 288v448c0 89.6 70.4 160 160 160h576c89.6 0 160-70.4 160-160V288c0-89.6-70.4-160-160-160z m96 608c0 54.4-41.6 96-96 96H224c-54.4 0-96-41.6-96-96V288c0-54.4 41.6-96 96-96h576c54.4 0 96 41.6 96 96v448z"
              p-id="5602"
            ></path>
            <path
              d="M240 384h64v64h-64zM368 384h384v64h-384zM432 576h352v64h-352zM304 576h64v64h-64z"
              p-id="5603"
            ></path>
          </svg>
          {{ handleNum(item.bulletCount) }}
        </div>
      </div>
    </div>
  </div>
</template>
<script>
import { handleNum, handleDuration } from "@/lib/utils";
import { mediaUrl } from "@/lib/media";
import videoPlaceholder from "@/assets/img/bilibili/video-placeholder.png";

export default {
  props: {
    item: {
      type: Object,
      required: true,
    },
  },
  methods: {
    mediaUrl,
    useFallbackImage(event) {
      event.target.src = videoPlaceholder;
    },
    // 处理大于1万的数字
    handleNum(number) {
      return handleNum(number);
    },
    handleDuration(duration) {
      return handleDuration(duration);
    },
  },
};
</script>

<style scoped>
/* .video-page-card-small {
  margin-bottom: 12px;
} */

.video-page-card-small a {
  color: #222;
  background-color: transparent;
  text-decoration: none;
  outline: none;
  cursor: pointer;
  transition: color 0.3s;
  -webkit-text-decoration-skip: objects;
}

.video-page-card-small .card-box {
  display: flex;
}

.video-page-card-small .card-box .pic-box {
  position: relative;
  width: 141px;
  height: 80px;
  border-radius: 6px;
  background: var(--graph_weak);
  flex: 0 0 auto;
}

.video-page-card-small .card-box .pic-box .pic {
  position: relative;
  overflow: hidden;
  border-radius: 6px;
  width: 100%;
  height: 100%;
  cursor: pointer;
}

.video-page-card-small .card-box .pic-box .pic img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  image-rendering: crisp-edges;
}

.video-page-card-small .card-box .pic-box .pic .duration {
  position: absolute;
  bottom: 6px;
  right: 6px;
  color: #fff;
  height: 20px;
  line-height: 20px;
  transition: opacity 0.3s;
  z-index: 5;
  font-size: 13px;
  background-color: rgba(0, 0, 0, 0.4);
  border-radius: 2px;
  padding: 0 4px;
}

.video-page-card-small .card-box .info {
  margin-left: 10px;
  flex: 1;
  font-size: 13px;
  line-height: 15px;
}

.video-page-card-small .card-box .info .title {
  cursor: pointer;
  color: var(--text1);
  display: block;
  font-size: 15px;
  line-height: 19px;
  transition: color 0.3s;
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  text-overflow: -o-ellipsis-lastline;
  text-overflow: ellipsis;
  word-break: break-word;
  -webkit-line-clamp: 2;
  -webkit-font-smoothing: antialiased;
}

.video-page-card-small .card-box .info .title:hover {
  color: var(--brand_blue);
}

.video-page-card-small .card-box .info .upname {
  cursor: pointer;
  margin: 2px 0;
  height: 20px;
  color: var(--text3);
  transition: color 0.3s;
  display: flex;
  align-items: center;
}

.video-page-card-small .card-box .info .upname:hover {
  color: var(--brand_blue);
}

.video-page-card-small .card-box .info .upname svg {
  margin-right: 4px;
  fill: var(--text3);
  transition: fill 0.3s;
}

.video-page-card-small .card-box .info .upname:hover svg {
  fill: var(--brand_blue);
}

.video-page-card-small .card-box .info .upname .name {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  text-overflow: -o-ellipsis-lastline;
  text-overflow: ellipsis;
  word-break: break-word;
  -webkit-line-clamp: 1;
}

.video-page-card-small .card-box .info .playinfo {
  color: var(--text3);
  fill: var(--text3);
  display: inline-flex;
  align-items: center;
}
.video-page-card-small .card-box .info .playinfo .playinfo-dm {
  margin-left: 8px;
}
.video-page-card-small .card-box .info .playinfo svg {
  margin-right: 4px;
}
</style>

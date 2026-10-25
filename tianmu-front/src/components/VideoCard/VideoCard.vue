<template>
  <div class="video-card">
    <!-- 骨架屏 -->
    <div
      class="video-card__skeleton"
      :class="isLoading ? 'loading_animation' : 'hide'"
    >
      <div class="video-card__skeleton--cover"></div>
      <div class="video-card__skeleton--info">
        <div class="video-card__skeleton--right">
          <p class="video-card__skeleton--text"></p>
          <p class="video-card__skeleton--text short"></p>
          <p class="video-card__skeleton--light"></p>
        </div>
      </div>
    </div>
    <!-- 实体内容——视频卡片 -->
    <div class="video-card__wrap" v-if="!isLoading">
      <!-- 视频上半部分——视频封面、播放数、弹幕数、时长 -->
      <a :href="`/video/${item.videoId}`" target="_blank">
        <div class="video-card__image">
          <!-- 视频封面 -->
          <div class="video-card__image--wrap">
            <picture class="video-card__cover">
              <img :src="item.coverUrl" alt="" />
            </picture>
          </div>
          <!-- 播放数、弹幕数、时长 -->
          <div class="video-card__mask">
            <div class="video-card__stats">
              <div class="video-card__stats--left">
                <span class="video-card__stats--item">
                  <i class="iconfont icon-bofangshu"></i>
                  <span class="video-card__stats--text">
                    {{ handleNum(item.viewCount) }}
                  </span>
                </span>
                <span class="video-card__stats--item">
                  <i class="iconfont icon-danmushu"></i>
                  <span class="video-card__stats--text">
                    {{ handleNum(item.bulletCount) }}
                  </span>
                </span>
              </div>
              <div class="video-card__stats__duration">
                {{ handleDuration(item.duration) }}
              </div>
            </div>
          </div>
        </div>
      </a>
      <!-- 视频下半部分——视频标题、是否关注、视频发布者用户名、上传日期 -->
      <div class="video-card__info">
        <div class="video-card__info--right">
          <!-- 视频标题 -->
          <h3 class="video-card__info--tit">
            <a
              v-if="dataType === 'search'"
              :href="`/video/${item.videoId}`"
              target="_blank"
              v-html="highlightKeyword(item.title)"
            >
            </a>
            <a v-else :href="`/video/${item.videoId}`" target="_blank">
              {{ item.title }}
            </a>
          </h3>
          <!-- 是否关注、视频发布者用户名、上传日期 -->
          <div class="video-card__info--bottom">
            <div class="video-card__info--icon-text" :style="'display: none;'">
              已关注
            </div>
            <a
              class="video-card__info--owner"
              :href="`/user/${item.userId}`"
              target="_blank"
            >
              <i class="iconfont icon-uper" :style="''"></i>
              <span class="video-card__info--author">{{ item.nickName }}</span>
              <span class="video-card__info--date">
                · {{ handleDate(item.createTime) }}
              </span>
            </a>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {
  handleNum,
  handleDuration,
  handleDate,
  highlightKeyword,
} from "@/lib/utils";

export default {
  props: {
    item: {
      type: [Object,Number],
      required: true,
    },
    isLoading: {
      type: Boolean,
      default() {
        return true;
      },
    },
    dataType: {
      type: String,
      default() {
        return "";
      },
    },
  },
  methods: {
    // 处理大于1万的数字
    handleNum(number) {
      return handleNum(number);
    },
    handleDuration(duration) {
      return handleDuration(duration);
    },
    handleDate(createTime) {
      return handleDate(createTime);
    },
    highlightKeyword(title) {
      return highlightKeyword(this.$route.query.keyword, title);
    },
  },
};
</script>

<style>
</style>
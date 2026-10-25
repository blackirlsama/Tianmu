<template>
  <!-- 骨架屏 -->
  <div
    class="video-card"
    :style="hasMoreVideo ? '' : 'display: none;'"
  >
    <div class="video-card__skeleton loading_animation">
      <div class="video-card__skeleton--cover"></div>
      <div class="video-card__skeleton--info">
        <div class="video-card__skeleton--right">
          <p class="video-card__skeleton--text"></p>
          <p class="video-card__skeleton--text short"></p>
          <p class="video-card__skeleton--light"></p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    hasMoreVideo: {
      type: Boolean,
      default() {
        return true;
      },
    },
  },
};
</script>

<style>
</style>
<template>
  <div class="user-card">
    <a :href="`/user/${item.userId}`">
      <div class="avatar">
        <img :src="item.avatar" :alt="item.nickname" />
      </div>
    </a>

    <div class="user-info">
      <div class="user-name">{{ item.nickname }}</div>
      <div class="user-desc">
        {{ item.description ? item.description : "这个人很懒，什么也没写" }}
      </div>
      <!-- <button class="follow-btn">已关注</button> -->
    </div>
  </div>
</template>

<script>
export default {
  name: "UserCenterCard",
  props: {
    item: {
      type: Object,
      default: () => {},
    },
  },
};
</script>

<style scoped lang="scss">
.user-card {
  background-color: white;
  border-radius: 10px;
  padding: 15px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
  display: flex;
  align-items: center;
  .avatar {
    width: 80px;
    height: 80px;
    margin-right: 15px;
    flex-shrink: 0;
    img {
      width: 100%;
      height: 100%;
      border-radius: 50%;
    }
  }
  .user-info {
    flex-grow: 1;
    display: flex;
    flex-direction: column;
    .user-name {
      font-size: 18px;
      font-weight: bold;
      color: #333;
    }

    .user-desc {
      font-size: 14px;
      color: #666;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      margin-top: 6px;
      margin-bottom: 8px;
    }

    .follow-btn {
      cursor: pointer;
      display: flex;
      justify-content: center;
      align-items: center;
      width: 96px;
      height: 35px;
      border-radius: 6px;
      font-size: 14px;
      font-weight: 700;
      color: var(--text_white);
      background-color: var(--main-color);
      transition: background-color 0.3s;
    }

    .follow-btn:hover {
      border-color: #00a1d6;
      color: #00a1d6;
    }
  }
}
</style>
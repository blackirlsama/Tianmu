<template>
  <div class="search-user">
    <div class="search-page i_wrapper">
      <div
        class="container"
        :style="
          userList.length === 0 && !loading
            ? 'grid-template-columns: unset;justify-content: center;'
            : ''
        "
      >
        <el-empty
          v-if="userList.length === 0 && !loading"
          description="暂无用户"
        />
        <template v-else>
          <div class="user-card" v-for="item in userList" :key="item.userId">
            <div class="user-info-card flex_start">
              <a
                :href="`/user/${item.userId}`"
                target="_blank"
                class="user-info-left"
              >
                <VAvatar :img="item.avatar" :size="86"></VAvatar>
              </a>
              <div class="user-content">
                <h2 class="card-title">
                  <a
                    :href="`/user/${item.userId}`"
                    target="_blank"
                    class="user-name"
                    :title="item.nickname"
                    v-html="highlightKeyword(item.nickname)"
                  >
                  </a>
                </h2>
                <p class="card-center">
                  {{ handleNum(item.followers) }}粉丝 ·
                  {{ handleNum(item.videoCount) }}个视频
                  <span style="margin-left: 3px">{{
                    item.description
                      ? item.description
                      : "这个人很懒，什么也没有写"
                  }}</span>
                </p>
                <!-- <div class="card-buttom">
                <button class="not-follow" v-if="true">+ 关注</button>
                <button class="following" v-else>已关注</button>
              </div> -->
              </div>
            </div>
          </div>
        </template>
      </div>
      <!-- <div class="search-bottom flex_center">
                <el-pagination
                    background
                    layout="prev, pager, next"
                    :total="this.$store.state.matchingCount[1]"
                    :page-size="30"
                    :pager-count="7"
                    :current-page="page"
                    @current-change="pageChange"
                ></el-pagination>
            </div> -->
    </div>
  </div>
</template>

<script>
import VAvatar from "@/components/avatar/VAvatar.vue";
import { handleNum, highlightKeyword } from "@/lib/utils.js";
import { fetchSearchUser } from "@/api/user";

export default {
  name: "SearchUser",
  components: {
    VAvatar,
  },
  data() {
    return {
      page: 1, // 当前页码
      userList: [], // 查询到的相关视频
      loading: true, // 正在查询中
    };
  },
  props: {
    // 从路由参数获取的关键词
    keyword: String,
  },
  methods: {
    // 查询相关视频
    async searchUsers() {
      try {
        this.userList = [];
        this.loading = true;
        const res = await fetchSearchUser(this.keyword);
        if (res.code !== 200) return;
        this.userList = res.data;
        this.loading = false;
      } catch (error) {
        console.log("查询相关用户出错：" + error);
      }
    },

    // 换页
    pageChange(page) {
      this.page = page;
      this.searchUsers();
    },

    // 处理大于一万的数字
    handleNum(number) {
      return handleNum(number);
    },
    // 高亮关键词
    highlightKeyword(text) {
      return highlightKeyword(this.keyword, text);
    },
  },
  created() {
    if (this.keyword) {
      this.searchUsers();
    }
  },
  watch: {
    keyword(curr) {
      if (curr) {
        this.page = 1;
        this.searchUsers();
      }
    },
  },
};
</script>

<style scoped>
.search-page {
  padding-bottom: 30px !important;
  margin-top: 30px !important;
  position: relative;
}

.container {
  grid-gap: 0px;
  display: grid;
  position: relative;
  width: 100%;
}

@media (max-width: 1699.9px) {
  .container {
    grid-column: span 2;
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (min-width: 1700px) {
  .container {
    grid-column: span 3;
    grid-template-columns: repeat(3, 1fr);
  }
}

.user-card {
  margin-bottom: 60px;
  padding: 0px 8px;
}

.user-info-left {
  margin-right: 15px;
}

.user-content {
  width: calc(100% - 101px);
  padding-right: 15px;
}

.card-title {
  margin-bottom: 5px;
  font-size: 18px;
  font-weight: 600;
  color: var(--text1);
  line-height: 1.25;
  display: flex;
  align-items: center;
}

.user-name {
  font-size: 18px;
  font-weight: 600;
  transition: color 0.2s;
  max-width: 100%;
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  display: inline-block;
  color: var(--text1);
}

.user-name:hover {
  color: var(--main-color);
}

.card-center {
  line-height: 1.35;
  margin: 5px 0;
  color: var(--text2);
  width: auto;
  max-width: 100%;
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.card-buttom button {
  height: 32px;
  padding: 0;
  width: 100px;
  border-radius: 6px;
  font-size: 14px;
  line-height: 1;
  white-space: nowrap;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  user-select: none;
  cursor: pointer;
  -webkit-transition-duration: 0.2s;
  transition-duration: 0.2s;
}

.not-follow {
  color: #fff;
  background: var(--main-color);
  border: 1px solid var(--main-color);
}

.not-follow:hover {
  color: #fff;
  background: var(--v_brand_blue_hover);
  border: 1px solid var(--v_brand_blue_hover);
}

.following {
  color: var(--text2);
  background: var(--graph_bg_regular);
  border: none;
}

.following:hover {
  background: var(--graph_bg_thick);
}

.search-bottom {
  margin: 50px 0 20px;
}
</style>
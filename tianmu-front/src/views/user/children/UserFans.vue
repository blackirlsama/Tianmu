<template>
  <div class="relationFans">
    <div class="section-header">
      <h2 class="section-title">
        <div class="title-text">
          {{ $route.params.userId === user.userId ? "我" : "他" }}的粉丝
        </div>
        <div class="section-desc">
          <span>·</span>
          {{ fansList.length }}
        </div>
      </h2>
    </div>
    <div
      class="users-grid"
      :style="
        fansList.length > 0
          ? ''
          : 'grid-template-columns: unset;justify-content: center'
      "
    >
      <template v-if="fansList.length">
        <UserCenterCardVue
          v-for="item in fansList"
          :item="item"
          :key="item.userId"
        ></UserCenterCardVue>
      </template>
      <el-empty v-else description="暂无粉丝" />
    </div>
  </div>
</template>

<script>
import UserCenterCardVue from "@/components/UserCenterCard/UserCenterCard.vue";
import { fetchFansList } from "@/api/user";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/store/user";
import { mapState } from "pinia";
export default {
  name: "UserFans",
  components: {
    UserCenterCardVue,
  },
  data() {
    return {
      fansList: [],
    };
  },
  created() {
    this.getFansList();
  },
  computed: {
    ...mapState(useUserStore, ["user"]),
  },
  methods: {
    async getFansList() {
      try {
        const res = await fetchFansList(this.$route.params.userId);
        if (res.code === 200) {
          this.fansList = res.data;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
  },
};
</script>

<style scoped lang="scss">
.relationFans {
  padding: 0 60px;
  margin-top: 30px;
  .section-header {
    display: flex;
    align-items: center;
    margin-bottom: 15px;
    .section-title {
      display: flex;
      align-items: center;
      .title-text {
        font-weight: bold;
        font-size: 24px;
        font-weight: 600;
        color: var(--v_text1);
      }
      .section-desc {
        font-size: 16px;
        margin-left: 6px;
        color: var(--v_text2);
      }
    }
  }
  .users-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 20px;
    margin-top: 20px;
  }
}
</style>
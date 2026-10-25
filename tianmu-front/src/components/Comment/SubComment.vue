<template>
  <div class="sub-reply-container">
    <!-- 展开剩余评论 -->
    <div class="view-more" v-if="!isShowMoreCmt && initSubCmtCount > 3">
      <div class="view-more-default">
        <span>共</span>
        <span>{{ subCommentList.length }}</span>
        <span>条回复, </span>
        <span class="view-more-btn" @click="isShowMoreCmt = true"
          >点击查看</span
        >
      </div>
    </div>
    <div v-else class="sub-reply-list">
      <div
        class="sub-reply-item"
        v-for="item in subCommentList"
        :key="item.commentId"
      >
        <div class="sub-reply-avatar-wrap">
          <a
            :href="`/user/${item.userId}`"
            target="_blank"
            class="sub-user-name"
            ><VAvatar
              :size="isWideWindow ? 30 : 24"
              :img="item.avatar"
            ></VAvatar
          ></a>
        </div>
        <div class="sub-user-info">
          <a
            :href="`/user/${item.userId}`"
            target="_blank"
            class="sub-user-name"
            >{{ item.nickname }}</a
          >
          <!-- UP主标识 -->
          <svg
            v-if="authorUid === item.userId"
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
          >
            <rect
              x="3"
              y="6"
              width="18"
              height="11.5"
              rx="2"
              fill="#FF6699"
            ></rect>
            <path
              d="M5.7 8.36V12.79C5.7 13.72 5.96 14.43 6.49 14.93C6.99 15.4 7.72 15.64 8.67 15.64C9.61 15.64 10.34 15.4 10.86 14.92C11.38 14.43 11.64 13.72 11.64 12.79V8.36H10.47V12.81C10.47 13.43 10.32 13.88 10.04 14.18C9.75 14.47 9.29 14.62 8.67 14.62C8.04 14.62 7.58 14.47 7.3 14.18C7.01 13.88 6.87 13.43 6.87 12.81V8.36H5.7ZM13.0438 8.36V15.5H14.2138V12.76H15.9838C17.7238 12.76 18.5938 12.02 18.5938 10.55C18.5938 9.09 17.7238 8.36 16.0038 8.36H13.0438ZM14.2138 9.36H15.9138C16.4238 9.36 16.8038 9.45 17.0438 9.64C17.2838 9.82 17.4138 10.12 17.4138 10.55C17.4138 10.98 17.2938 11.29 17.0538 11.48C16.8138 11.66 16.4338 11.76 15.9138 11.76H14.2138V9.36Z"
              fill="white"
            ></path>
          </svg>
        </div>
        <span class="reply-content-container sub-reply-content">
          <span class="reply-content">
            <span v-if="item.parentCommentId !== rootCommentId"
              >回复
              <a
                class="jump-link"
                :href="`/user/${item.toUserId}`"
                target="_blank"
                >@{{ item.toNickname }}</a
              >
              :
            </span>
            <span v-html="item.content"></span>
          </span>
        </span>
        <div class="sub-reply-info">
          <span class="sub-reply-time">{{
            formatStandardDateTime(item.createTime)
          }}</span>
          <span class="sub-reply-btn" @click="handleReply(item)">回复</span>
          <div
            class="sub-reply-operation-wrap"
            v-if="this.user.userId === item.userId"
          >
            <div class="reply-operation">
              <i
                class="svg-icon operation-icon"
                style="width: 16px; height: 16px"
                @click="showOption = !showOption"
              >
                <svg
                  t="1709531772895"
                  class="icon"
                  viewBox="0 0 1024 1024"
                  version="1.1"
                  xmlns="http://www.w3.org/2000/svg"
                  p-id="4844"
                  width="100%"
                  height="100%"
                >
                  <path
                    d="M413.602909 831.301818a97.000727 97.000727 0 1 0 194.094546 0 97.000727 97.000727 0 0 0-194.094546 0z m0-309.480727a97.000727 97.000727 0 1 0 194.094546 0 97.000727 97.000727 0 0 0-194.094546 0z m0-328.797091a97.000727 97.000727 0 1 0 194.094546 0 97.000727 97.000727 0 0 0-194.094546 0z"
                    p-id="4845"
                  ></path>
                </svg>
              </i>
              <ul class="operation-list">
                <li class="operation-option" @click="deleteComment(item)">
                  <span class="option-title">删除</span>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import VAvatar from "../avatar/VAvatar.vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { handleNum, formatStandardDateTime } from "@/lib/utils.js";
import { useUserStore } from "@/store/user";
import { mapState } from "pinia";
import { fetchDeleteComment } from "@/api/video.js";

export default {
  name: "SubComment",
  components: {
    VAvatar,
  },
  props: {
    // 是否是宽屏
    isWideWindow: {
      type: Boolean,
      default() {
        return false;
      },
    },
    // 子级评论列表数据
    subCommentList: {
      type: Array,
      default() {
        return [];
      },
    },
    // 视频作者uid
    rootCommentId: {
      type: String,
      default() {
        return "";
      },
    },
    // 视频作者uid
    authorUid: {
      type: String,
      default() {
        return "";
      },
    },
  },
  data() {
    return {
      isShowMoreCmt: false, //是否展示更多评论
      initSubCmtCount: 0, //初始子评论数
    };
  },
  computed: {
    ...mapState(useUserStore, ["user"]),
  },
  mounted() {
    this.initSubCmtCount = this.subCommentList.length;
  },
  methods: {
    // 计算大于1万的数字
    handleNum(number) {
      return handleNum(number);
    },
    // 计算时间
    // 处理评论的时间
    formatStandardDateTime(time) {
      return formatStandardDateTime(time);
    },
    // 处理子评论的回复
    handleReply(subComment) {
      this.$emit("sub-reply", subComment);
    },

    async moreComment() {},

    // 展开更多子评论
    async getMoreComment() {},

    // 删除子评论
    async deleteComment(subComment) {
      ElMessageBox.confirm(
        "删除评论后，评论下所有回复都会被删除,是否继续?",
        "提示",
        {
          confirmButtonText: "确定",
          cancelButtonText: "取消",
          type: "warning",
        }
      )
        .then(async () => {
          try {
            const res = await fetchDeleteComment(
              subComment.commentId,
              this.$route.params.videoId
            );
            if (res.code === 200) {
              ElMessage.success("删除评论成功");
              this.$emit("del-subComment", subComment);
            }
          } catch (error) {
            ElMessage.error("删除评论失败：" + error);
          }
        })
        .catch(() => {});
    },

    // 换页
  },
};
</script>

<style scoped>
.sub-reply-container {
  margin-top: 5px;
  padding-left: 72px;
}

.sub-reply-item {
  position: relative;
  padding: 8px 0 8px 42px;
  border-radius: 4px;
}

@media screen and (max-width: 1681px) {
  .sub-reply-item {
    font-size: 15px;
    line-height: 24px;
  }
}

@media screen and (min-width: 1681px) {
  .sub-reply-item {
    font-size: 16px;
    line-height: 26px;
  }
}

.sub-reply-item .sub-user-info {
  display: inline-flex;
  align-items: center;
  margin-right: 9px;
  line-height: 24px;
  vertical-align: baseline;
  white-space: nowrap;
}

.sub-reply-avatar-wrap {
  position: absolute;
  left: 8px;
}

.sub-reply-avatar {
  cursor: pointer;
}

.sub-user-name {
  font-size: 13px;
  line-height: 24px;
  font-weight: 500;
  margin-right: 7px;
  color: var(--text2);
  cursor: pointer;
}

.reply-content-container .reply-content {
  color: var(--text1);
  overflow: hidden;
  word-wrap: break-word;
  word-break: break-word;
  white-space: pre-wrap;
  line-height: 24px;
  vertical-align: baseline;
}

.jump-link {
  color: var(--text_link);
  cursor: pointer;
}

.jump-link:hover {
  color: var(--brand_blue);
}

.sub-reply-item .sub-reply-info {
  display: flex;
  align-items: center;
  position: relative;
  margin-top: 2px;
  font-size: 13px;
  color: var(--text3);
}

.sub-reply-time {
  margin-right: 20px;
}

.svg-icon {
  display: inline-flex;
  justify-content: center;
  align-items: center;
}

.sub-reply-btn {
  cursor: pointer;
}

.sub-reply-btn:hover {
  color: var(--main-color);
}
.sub-reply-item:hover .sub-reply-operation-wrap {
  display: block;
}
.sub-reply-operation-wrap {
  display: none;
  position: absolute;
  right: 40px;
}
.sub-reply-operation-wrap:hover .operation-list {
  display: flex;
}
.reply-operation {
  display: inline-flex;
  position: relative;
}

.reply-operation .operation-icon {
  fill: #9499a0;
}

.icon:hover {
  fill: var(--main-color);
  cursor: pointer;
}
.operation-list {
  display: none;
  flex-direction: column;
  position: absolute;
  top: 20px;
  right: 0;
  z-index: 10;
  width: 120px;
  border-radius: 4px;
  font-size: 14px;
  color: var(--v_text1);
  background-color: var(--bg1);
  box-shadow: 0 0 5px #0003;
}

.operation-list .operation-option {
  display: flex;
  align-items: center;
  height: 36px;
  padding: 0 15px;
  cursor: pointer;
}

.operation-list .operation-option:hover {
  background-color: var(--bg3);
  color: var(--main-color);
}

.view-more {
  padding-left: 8px;
  font-size: 13px;
  color: var(--text3);
}

.view-more-btn {
  cursor: pointer;
}

.view-more-btn:hover {
  color: var(--brand_pink);
}

.view-more-pagination {
  color: var(--text1);
}

.pagination-page-count {
  margin-right: 10px;
}

.pagination-btn {
  cursor: pointer;
}

.pagination-page-number {
  margin: 0 4px;
  cursor: pointer;
}

.pagination-btn:hover,
.pagination-page-number:hover,
.current-page {
  color: var(--brand_pink);
}
</style>
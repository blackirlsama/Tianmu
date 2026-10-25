<template>
  <div class="reply-list">
    <div
      class="reply-item"
      v-for="(item, index) in isLogin ? commentList : commentList.slice(0, 2)"
      :key="item.commentId"
    >
      <!-- 第二楼特有的未登录蒙版 -->
      <div class="login-limit-mask" v-if="!isLogin && index === 1">
        <div class="mask-top"></div>
        <div class="mask-bottom"></div>
      </div>
      <div class="root-reply-container">
        <div class="root-reply-avatar-wrap">
          <a
            :href="`/user/${item.userId}`"
            target="_blank"
            class="sub-user-name"
            ><VAvatar
              :size="isWideWindow ? 48 : 40"
              :img="item.avatar"
            ></VAvatar
          ></a>
        </div>
        <div class="content-wrap">
          <div class="user-info">
            <a
              :href="`/user/${item.userId}`"
              target="_blank"
              class="user-name"
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
          <div class="root-reply">
            <span class="reply-content-container root-reply">
              <span class="reply-content" v-html="item.content"></span>
            </span>
            <div class="reply-info">
              <span class="reply-time">{{
                formatStandardDateTime(item.createTime)
              }}</span>
              <span class="reply-btn" @click="handleReply(item)">回复</span>
              <div
                class="reply-operation-wrap"
                v-if="this.user.userId === item.userId"
              >
                <div class="reply-operation">
                  <i
                    class="svg-icon operation-icon"
                    style="width: 16px; height: 16px"
                  >
                    <svg
                      t="1709522739467"
                      class="icon"
                      viewBox="0 0 1024 1024"
                      version="1.1"
                      xmlns="http://www.w3.org/2000/svg"
                      p-id="4290"
                      width="100%"
                      height="100%"
                    >
                      <path
                        d="M413.602909 831.301818a97.000727 97.000727 0 1 0 194.094546 0 97.000727 97.000727 0 0 0-194.094546 0z m0-309.480727a97.000727 97.000727 0 1 0 194.094546 0 97.000727 97.000727 0 0 0-194.094546 0z m0-328.797091a97.000727 97.000727 0 1 0 194.094546 0 97.000727 97.000727 0 0 0-194.094546 0z"
                        p-id="4291"
                      ></path>
                    </svg>
                  </i>
                  <ul
                    class="operation-list"
                    :style="{ display: true ? '' : 'none' }"
                  >
                    <li
                      class="operation-option"
                      v-if="this.user.userId === item.userId"
                      @click="deleteComment(item.commentId)"
                    >
                      <span class="option-title"> 删除 </span>
                    </li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <!-- 子评论 -->
      <SubComment
        v-if="item.children"
        :subCommentList="item.children"
        :authorUid="authorUid"
        :rootCommentId="item.commentId"
        @sub-reply="handleReply"
        @del-subComment="delSubComment"
        :delOneComment="delOneComment"
      />
      <div
        class="reply-box-container"
        v-if="commentInfo.rootCommentId === item.commentId"
      >
        <div class="reply-box box-active">
          <ReplyTextarea
            ref="ReplyTextareaRef"
            :placeholder="replyPlaceHolder"
            :isWideWindow="isWideWindow"
            :commentInfo="commentInfo"
            @add-comment="addComment"
            :addOneComment="addOneComment"
          ></ReplyTextarea>
        </div>
      </div>
      <div class="bottom-line"></div>
    </div>
    <div class="reply-loading" v-if="loading">正在加载...</div>
    <div class="no-any" v-if="commentList.length === 0 && !loading">
      视频还没有任何评论哦，快来抢占沙发位吧~
    </div>
    <div
      class="no-more"
      v-if="
        (isLogin && commentList.length !== 0) ||
        (!isLogin && commentList.length === 1)
      "
    >
      已经到底啦~
    </div>
    <div
      class="login-prompt"
      v-if="!isLogin && commentList.length >= 2"
      @click="toLogin"
    >
      登录后查看 {{ commentList.length }}+ 条评论
    </div>
  </div>
</template>

<script>
import VAvatar from "@/components/avatar/VAvatar.vue";
import SubComment from "@/components/Comment/SubComment.vue";
import ReplyTextarea from "@/components/Comment/ReplyTextarea.vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { fetchCommentList, fetchDeleteComment } from "@/api/video.js";
import { handleNum, formatStandardDateTime } from "@/lib/utils.js";
import { useUserStore } from "@/store/user";
import { mapState, mapActions } from "pinia";
export default {
  name: "CommentTree",
  components: {
    VAvatar,
    SubComment,
    ReplyTextarea,
  },
  data() {
    return {
      replyPlaceHolder: "", // 控制 回复@用户名：
      // 回复评论参数
      commentInfo: {
        content: "",
        userId: "",
        videoId: "",
        parentCommentId: "",
        rootCommentId: "",
      },
      lastClickCmtId: "", //上一次点击回复的评论的id
      commentList: [],
      loading: false, // 是否正在加载评论
    };
  },
  props: {
    // 是否是宽屏
    isWideWindow: {
      type: Boolean,
      default() {
        return false;
      },
    },
    // 视频作者uid
    authorUid: {
      type: String,
      default() {
        return "";
      },
    },
    // 增加一条评论
    addOneComment: {
      type: Function,
      default() {
        return () => {};
      },
    },
    // 删除一条评论
    delOneComment: {
      type: Function,
      default() {
        return () => {};
      },
    },
  },
  created() {
    this.getCommentList();
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin"]),
  },
  methods: {
    ...mapActions(useUserStore, ["updateDialogVisible"]),
    toLogin() {
      this.updateDialogVisible(true);
    },
    // 获取评论列表
    async getCommentList() {
      this.loading = true;

      try {
        const res = await fetchCommentList(this.$route.params.videoId);
        if (res.code === 200) {
          this.commentList = res.data;
          this.loading = false;
        }
      } catch (error) {
        ElMessage.error("获取评论失败：" + error);
      }
    },

    // 删除根评论
    async deleteComment(id) {
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
              id,
              this.$route.params.videoId
            );
            if (res.code === 200) {
              ElMessage.success("删除评论成功");
              let delCount = 0;
              this.commentList = this.commentList.filter((item) => {
                if (item.commentId !== id) {
                  return true;
                } else {
                  // 根评论数量+子评论数量——删除的评论数量
                  delCount = item.children?.length + 1;
                }
              });
              this.delOneComment(delCount);
            }
          } catch (error) {
            ElMessage.error(error);
          }
        })
        .catch(() => {});
    },

    // 删除子评论
    delSubComment(comment) {
      let rootItem = this.getRootComment(this.commentList, comment);
      if (rootItem) {
        const idsToDelete = this.getAllCommentsToDelete(
          rootItem,
          comment.commentId
        );
        rootItem.children = rootItem.children.filter(
          (comment) => !idsToDelete.includes(comment.commentId)
        );
        // idsToDelete.length是删除的评论数量F
        this.delOneComment(idsToDelete.length);
      }
    },

    getAllCommentsToDelete(rootItem, commentId) {
      let idsToDelete = [commentId];

      function findChildren(parentCommentId) {
        rootItem.children.forEach((comment) => {
          if (comment.parentCommentId === parentCommentId) {
            idsToDelete.push(comment.commentId);
            findChildren(comment.commentId); // 递归查找子评论
          }
        });
      }

      findChildren(commentId); // 从指定评论开始查找子评论
      return idsToDelete;
    },
    // 清空评论列表
    clearCommentList() {},

    // 新插入评论的回调
    addComment(comment) {
      // 只要有parentCommentId就是子评论（后代评论），否则是根评论
      if (comment.parentCommentId) {
        // 回复评论尾插
        let rootItem = this.getRootComment(this.commentList, comment);

        if (rootItem) {
          if (rootItem.children) {
            rootItem.children.push(comment);
          } else {
            // 没有children就创建children属性
            rootItem.children = [comment];
          }
        }
      } else {
        // 根评论头插
        this.commentList.unshift(comment);
      }
    },

    // 更新获取到的全部子评论
    updateComment(comment) {},
    // 渲染评论中的表情包
    // emojiText(text) {
    //     return emojiText(text);
    // },
    // 处理大于1万的数字
    handleNum(number) {
      return handleNum(number);
    },
    // 处理评论的时间
    formatStandardDateTime(time) {
      return formatStandardDateTime(time);
    },

    // 控制回复框的聚焦；事件
    cancelFocus() {},

    // 触底加载
    async handleScroll() {},

    // 处理评论的commentInfo
    handleReply(comment) {
      if (!this.isLogin) {
        return ElMessage.warning("请先登录");
      }
      // 当再次点击同一个评论时，清空评论信息
      if (comment.commentId === this.lastClickCmtId) {
        this.commentInfo = {
          content: "",
          userId: "",
          videoId: "",
          parentCommentId: "",
          rootCommentId: "",
        };
        this.lastClickCmtId = "";
        return;
      }
      // 记录上次点击回复的评论id
      this.lastClickCmtId = comment.commentId;

      // 根评论没有parentCommentId，可以根据这个回复的是子评论还是根评论，同时可以根据这个判断是否拥有同一个根评论来控制回复框的显示
      if (comment.parentCommentId) {
        // 回复子评论或者后代评论
        let rootItem = this.getRootComment(this.commentList, comment);
        this.commentInfo.rootCommentId = rootItem?.commentId || "";
        this.commentInfo.parentCommentId = comment.commentId;
      } else {
        // 回复根评论，再次点击根评论时，记得重置pid
        this.commentInfo.rootCommentId = comment.commentId;
        this.commentInfo.parentCommentId = comment.commentId;
      }

      this.replyPlaceHolder = "回复 @" + comment.nickname + "：";
      this.$nextTick(() => {
        // 让回复输入框聚焦
        this.$refs.ReplyTextareaRef[0].focusTextarea();
      });
    },

    // 根据子评论获取根评论
    getRootComment(commentList, comment) {
      for (let rootComment of commentList) {
        if (rootComment.commentId === comment.parentCommentId) {
          return rootComment;
        }

        let found = rootComment.children?.find(
          (child) => child.commentId === comment.parentCommentId
        );
        if (found) {
          return rootComment;
        }
      }
      return null;
    },
  },
  mounted() {
    window.addEventListener("scroll", this.handleScroll);
  },
  beforeUnmount() {
    window.removeEventListener("scroll", this.handleScroll);
  },
  watch: {},
};
</script>

<style scoped>
.reply-list {
  margin-top: 14px;
  padding-bottom: 100px;
}

.reply-item {
  position: relative;
}

.root-reply-container {
  padding: 22px 0 0 80px;
}

.login-limit-mask {
  position: absolute;
  top: 0;
  right: 0;
  width: 100%;
  height: 100%;
  z-index: 10;
  /* pointer-events: none; */
}

.mask-top {
  height: 80%;
  background: linear-gradient(
    180deg,
    rgba(255, 255, 255, 0) 0%,
    var(--bg1) 100%
  );
}

.mask-bottom {
  height: 20%;
  background: var(--bg1);
  pointer-events: initial;
}

.root-reply-avatar-wrap {
  display: flex;
  justify-content: center;
  position: absolute;
  left: 0;
  width: 80px;
}

.root-reply-avatar {
  cursor: pointer;
}

.content-wrap {
  flex: 1;
  position: relative;
}

.user-info {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
}

/* @media screen and (max-width:1681px) {
    .user-info {
        font-size: 13px;
    }
} */

.user-name {
  font-weight: 500;
  margin-right: 7px;
  color: var(--text2);
  cursor: pointer;
}

.level {
  margin-right: 8px;
}

.root-reply {
  position: relative;
  padding: 2px 0;
}

@media screen and (max-width: 1681px) {
  .root-reply {
    font-size: 15px;
    line-height: 24px;
  }
}

@media screen and (min-width: 1681px) {
  .root-reply {
    font-size: 16px;
    line-height: 26px;
  }
}

.reply-content-container {
  display: block;
  overflow: hidden;
  width: 100%;
}

.root-reply {
  position: relative;
  padding: 2px 0;
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

.content-wrap .root-reply .reply-info {
  display: flex;
  align-items: center;
  position: relative;
  margin-top: 2px;
  font-size: 13px;
  color: var(--text3);
}

.root-reply .reply-info .reply-time {
  margin-right: 20px;
}

.root-reply .reply-info .reply-like {
  display: flex;
  align-items: center;
  margin-right: 19px;
  cursor: pointer;
}

.svg-icon {
  display: inline-flex;
  justify-content: center;
  align-items: center;
}

.icon-2 {
  fill: var(--brand_pink);
}

.root-reply .reply-info .reply-dislike {
  display: flex;
  align-items: center;
  margin-right: 19px;
}

.root-reply .reply-info .reply-btn {
  cursor: pointer;
}

.root-reply .reply-info .reply-btn:hover {
  color: var(--main-color);
}

.reply-operation-wrap {
  position: absolute;
  right: 20px;
  display: none;
}

.root-reply-container:hover .reply-operation-wrap {
  display: block;
}
.reply-operation-wrap:hover .operation-list {
  display: flex;
}
.icon {
  fill: #9499a0;
}

.icon:hover {
  fill: var(--main-color);
  cursor: pointer;
}

.reply-operation {
  display: inline-flex;
  position: relative;
}

.svg-icon {
  display: inline-flex;
  justify-content: center;
  align-items: center;
}

.reply-operation .operation-icon {
  color: #9499a0;
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

.reply-item .reply-box-container {
  padding: 25px 0 10px 80px;
}

.reply-box {
  display: flex;
  flex-direction: column;
}

.bottom-line {
  margin-left: 80px;
  border-bottom: 1px solid var(--v_graph_bg_thick);
  margin-top: 14px;
}

.reply-loading,
.no-any,
.no-more {
  margin-top: 20px;
  font-size: 13px;
  color: var(--text3);
  text-align: center;
}

.login-prompt {
  display: flex;
  align-items: center;
  justify-content: center;
  width: calc(100% - 80px);
  height: 50px;
  margin: 16px 0 0 auto;
  border-radius: 6px;
  font-size: 14px;
  color: var(--main-color);
  background-color: var(--v_brand_blue_thin);
  transition: 0.2s;
  cursor: pointer;
}
</style>
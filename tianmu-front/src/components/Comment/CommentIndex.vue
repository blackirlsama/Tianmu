<template>
  <div class="comment">
    <div class="tianmu-comment browser-pc">
      <div class="comment-container">
        <div class="reply-header">
          <!-- 加载 -->
          <!-- 导航栏 -->
          <div class="reply-navigation">
            <ul class="nav-bar">
              <li class="nav-title">
                <span class="nav-title-text">评论</span>
                <span class="total-reply">{{ commentCount }}</span>
              </li>
            </ul>
          </div>
        </div>
        <!-- 评论区 -->
        <div class="reply-wrap">
          <div class="main-reply-box">
            <div class="reply-box">
              <ReplyTextarea
                ref="rootReply"
                :isWideWindow="isWideWindow"
                @add-comment="addComment"
                :addOneComment="addOneComment"
              ></ReplyTextarea>
            </div>
          </div>
          <CommentTree
            ref="CommentTree"
            :isWideWindow="isWideWindow"
            :authorUid="authorUid"
            :addOneComment="addOneComment"
            :delOneComment="delOneComment"
          ></CommentTree>
          <div
            class="fixed-reply-box"
            v-if="isLogin"
            :class="isHideReplyBox ? 'reply-box-hide' : 'reply-box-show'"
            :style="`--left: ${left}px; --width: ${width}px; display: ${replyBoxDisplay};`"
          >
            <i class="reply-box-shadow"></i>
            <div class="reply-box fixed-box">
              <ReplyTextarea
                ref="FixReplyBoxRef"
                @add-comment="addComment"
                :isWideWindow="isWideWindow"
                :addOneComment="addOneComment"
              ></ReplyTextarea>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import CommentTree from "@/components/Comment/CommentTree.vue";
import ReplyTextarea from "@/components/Comment//ReplyTextarea.vue";
import { mapState } from "pinia";
import { useUserStore } from "@/store/user";
export default {
  name: "CommentIndex",
  components: {
    CommentTree,
    ReplyTextarea,
  },
  props: {
    // 评论数量
    commentCount: {
      type: Number,
      default() {
        return 0;
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
  data() {
    return {
      isWideWindow: false, // 是否是宽屏
      left: 0, // 固钉评论框左偏移量
      width: 0, // 固钉评论框宽度
      // 是否隐藏回复框
      isHideReplyBox: true,
      replyBoxDisplay: "none",
    };
  },

  mounted() {
    this.$nextTick(() => {
      this.resize();
      // this.handleScroll();
    });
    window.addEventListener("click", this.handleOutSideClick);
    window.addEventListener("scroll", this.handleScroll);
    window.addEventListener("resize", this.resize);
  },
  beforeUnmount() {
    window.removeEventListener("click", this.handleOutSideClick);
    window.removeEventListener("scroll", this.handleScroll);
    window.removeEventListener("resize", this.resize);
  },
  computed: {
    ...mapState(useUserStore, ["isLogin"]),
  },
  methods: {
    // 点击空白处关闭回复框
    handleOutSideClick(event) {
      event.stopPropagation();
      // 排除这些元素，如果点击这些元素，不触发关闭事件
      const text = document.querySelectorAll(".textarea-wrap");
      const emoji = document.querySelectorAll(".reply-box-emoji");
      const sendComment = document.querySelectorAll(".reply-box-send");
      const replyBtn = document.querySelectorAll(".reply-btn");
      const subReplyBtn = document.querySelectorAll(".sub-reply-btn");
      const emojibox = document.querySelectorAll(".emoji-box");
      const replyBoxTextarea = document.querySelectorAll(".comment-textarea");

      const elements = [
        ...text,
        ...emoji,
        ...sendComment,
        ...replyBtn,
        ...subReplyBtn,
        ...emojibox,
        ...replyBoxTextarea,
      ];

      for (let i = 0; i < elements.length; i++) {
        if (elements[i].contains(event.target)) return;
      }
      // debugger

      this.$nextTick(() => {
        this.$refs.rootReply.cancelFocus();
        // this.$refs.CommentTree.cancelFocus();
        this.$refs.FixReplyBoxRef?.cancelFocus();
      });
    },
    addComment(comment) {
      this.$refs.CommentTree.addComment(comment);
    },
    // 窗口大小变动时的回调
    resize() {
      // 设置底部回复框的宽度和左偏移量
      this.isWideWindow = window.innerWidth >= 1620;
      this.width = document.querySelector(".reply-wrap").clientWidth;
      this.left = document
        .querySelector(".reply-wrap")
        .getBoundingClientRect().left;
    },
    // 回复框滚动隐藏效果
    async handleScroll() {
      const inputElement = document.querySelector(".main-reply-box");
      const offsetButtom = inputElement.getBoundingClientRect().bottom;
      if (offsetButtom < 0 && this.isHideReplyBox) {
        this.replyBoxDisplay = "";
        this.isHideReplyBox = false;
      } else if (offsetButtom >= 0 && !this.isHideReplyBox) {
        this.isHideReplyBox = true;
        setTimeout(() => {
          this.replyBoxDisplay = "none";
        }, 300);
      }
    },
  },

  watch: {},
};
</script>

<style scoped>
.comment {
  margin-top: 24px;
  z-index: 0;
  position: relative;
}

.tianmu-comment .browser-pc {
  background-color: #ffffff;
}

.comment-container {
  animation-name: enterAnimation-commentContainer;
  animation-duration: 1s;
  animation-fill-mode: forwards;
}

.reply-navigation {
  margin-bottom: 22px;
}

.nav-bar {
  display: flex;
  align-items: center;
  list-style: none;
  margin: 0;
  padding: 0;
}

.nav-title {
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav-title .nav-title-text {
  color: black;
  font-size: 20px;
  font-weight: 500;
}

.total-reply {
  margin: 0 36px 0 6px;
  font-weight: 400;
  color: #9499a0;
}

@media screen and (max-width: 1681px) {
  .total-reply {
    font-size: 13px;
  }
}

@media screen and (min-width: 1681px) {
  .total-reply {
    font-size: 14px;
  }
}

.reply-wrap {
  position: relative;
}

.reply-box {
  display: flex;
  flex-direction: column;
}

.fixed-reply-box {
  position: fixed;
  bottom: 0;
  left: var(--left);
  z-index: 10;
  width: var(--width);
}

.reply-box-hide {
  animation: fade-out-bottom 0.3s ease-out forwards;
  transform-origin: bottom;
}

.reply-box-show {
  animation: fade-in-bottom 0.3s ease-out forwards;
  transform-origin: bottom;
}

@keyframes fade-in-bottom {
  0% {
    opacity: 0;
    transform: translateY(5px);
  }

  100% {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes fade-out-bottom {
  0% {
    opacity: 1;
    transform: translateY(0);
  }

  100% {
    opacity: 0;
    transform: translateY(5x);
  }
}

.reply-box-shadow {
  position: absolute;
  top: -10px;
  z-index: 1;
  width: 100%;
  height: 36px;
  border-radius: 50%;
  background-color: #00000014;
  filter: blur(10px);
}

.reply-box.fixed-box {
  position: relative;
  z-index: 2;
  padding: 15px 0;
  border-top: 0.5px solid #e3e5e7;
  background-color: #ffffff;
}

.reply-box {
  display: flex;
  flex-direction: column;
}
</style>
<template>
  <div class="videoDetail">
    <HeaderBar :isFixHeaderBar="true"></HeaderBar>
    <div class="video-container">
      <!-- 左边视频区域 -->
      <div class="left-container" :style="`width: ${playerSize.width}px;`">
        <!-- 标题、排名、播放数、弹幕数、发布时间 -->
        <div class="video-info-container">
          <h1 title="视频标题" class="video-title">{{ videoDetail.title }}</h1>
          <div class="video-info-detail">
            <div class="video-info-detail-list">
              <span class="view item">
                <i class="iconfont icon-bofangshu"></i>
                &nbsp;{{ handleNum(videoDetail.viewCount) }}&nbsp;
              </span>
              <span class="danmu item">
                <i class="iconfont icon-danmushu"></i>
                &nbsp;{{ handleNum(danmuList.length) }}&nbsp;
              </span>
              <span class="date item">
                {{ formatStandardDateTime(videoDetail.createTime) }}
              </span>
              <span class="copyright item" v-if="true">
                <i class="iconfont icon-jinzhi"></i>
                未经作者授权，禁止转载
              </span>
            </div>
          </div>
        </div>
        <!-- 播放器组件 -->
        <Artplayer
          ref="artplayerRef"
          v-if="videoDetail.fileUrl"
          :videoUrl="mediaVideoUrl(videoDetail.fileUrl)"
          :videoId="videoDetail.videoId"
          @sendDanmu="sendDanmu"
          :onlineUserCount="onlineUserCount"
          :danmuList="danmuList"
          :seekTimePoint="seekTimePoint"
          :isSelfDanmu="isSelfDanmu"
        />
        <!-- <div style="height: 424px; width: 672px; background: black"></div> -->
        <!-- 三连转发 -->
        <div class="video-toolbar-container">
          <!-- 左边的互动操作——点赞、不喜欢、投币、收藏、分享 -->
          <div class="video-toolbar-left">
            <!-- 点赞 -->
            <div class="toolbar-left-item-wrap" title="点赞（Q）">
              <div
                class="video-toolbar-left-item"
                :class="{ on: isLike }"
                @mousedown="startLongPress('like')"
                @mouseup="endLongPress('like')"
              >
                <i class="iconfont icon-dianzan btn" ref="likeBtn">
                  <canvas class="progress-ring" ref="likeProgress"></canvas>
                </i>
                <span class="video-toolbar-item-text">{{ likeCount }}</span>
                <div
                  class="dianzan-gif"
                  :class="false ? 'gif-show' : 'gif-hide'"
                >
                  <img src="@/assets/img/icon_hot.png" alt="" v-if="false" />
                </div>
              </div>
            </div>
            <!-- 投币 -->
            <div
              class="toolbar-left-item-wrap"
              :title="isCoin ? '对本稿件的投币数已经用完' : '投币'"
            >
              <div
                class="video-toolbar-left-item"
                :class="{ on: isCoin }"
                @click="handleCoin"
              >
                <i class="iconfont icon-toubi btn" ref="coinBtn"
                  ><canvas class="progress-ring" ref="coinProgress"></canvas
                ></i>
                <span class="video-toolbar-item-text">{{ coinCount }}</span>
              </div>
            </div>
            <!-- 收藏 -->
            <div class="toolbar-left-item-wrap" title="收藏（E）">
              <div
                class="video-toolbar-left-item"
                :class="{ on: isFavorite }"
                @click="handleCollect"
              >
                <i class="iconfont icon-shoucang1 btn" ref="collectBtn">
                  <canvas class="progress-ring" ref="collectProgress"></canvas>
                </i>
                <span class="video-toolbar-item-text">{{ favoriteCount }}</span>
              </div>
            </div>
          </div>
        </div>
        <!-- 视频简介和评论区 -->
        <div class="left-container-under-player">
          <!-- 简介 -->
          <div
            class="video-desc-container"
            :style="false ? 'display: none;' : ''"
          >
            <div
              class="basic-desc-info"
              :style="showAllDesc ? 'height: auto;' : 'height: 84px;'"
            >
              <span
                class="desc-info-text"
                v-html="handleLinkify(videoDetail.description)"
              ></span>
            </div>
            <div class="toggle-btn" v-if="descTooLong">
              <span class="toggle-btn-text" @click="showAllDesc = !showAllDesc">
                {{ showAllDesc ? "收起" : "展开更多" }}
              </span>
            </div>
          </div>
          <!-- 分类、标签 -->
          <div class="video-tag-container" v-if="videoDetail.tags">
            <div
              class="tag-container"
              v-for="(item, index) in JSON.parse(videoDetail.tags)"
              :key="index"
            >
              <a
                :href="`/search/video?keyword=${item}`"
                target="_blank"
                class="tag-link"
                >{{ item }}</a
              >
            </div>
          </div>
          <!-- 评论 -->
          <CommentIndex
            :commentCount="commentCount"
            :authorUid="videoDetail.userId"
            :addOneComment="addOneComment"
            :delOneComment="delOneComment"
          ></CommentIndex>
        </div>
      </div>
      <!-- 右边弹幕列表、推荐视频区域 -->
      <div class="right-container">
        <div class="right-container-inner">
          <!-- UP主信息 -->
          <div class="up-panel-container">
            <div class="up-info-container">
              <!-- up主头像 -->
              <div class="up-info--left">
                <div class="up-avatar-wrap">
                  <VPopover
                    popStyle="z-index: 2000; cursor: default; padding-top: 30px;"
                  >
                    <template #reference>
                      <a
                        :href="`/user/${videoUserInfo.userId}`"
                        target="_blank"
                        class="up-avatar"
                      >
                        <VAvatar
                          :img="videoUserInfo.avatar"
                          :size="48"
                        ></VAvatar>
                      </a>
                    </template>
                  </VPopover>
                </div>
              </div>
              <!-- 关注信息 -->
              <div class="up-info--right">
                <!-- up主信息 -->
                <div class="up-info__detail">
                  <div class="up-detail-top">
                    <a
                      :href="`/user/${videoDetail.userId}`"
                      target="_blank"
                      class="up-name"
                      >{{ videoUserInfo.nickname }}</a
                    >
                  </div>
                  <div class="up-description">
                    {{
                      videoUserInfo.description
                        ? videoUserInfo.description
                        : "这个人很懒，什么也没写"
                    }}
                  </div>
                </div>
                <!-- 关注状态 -->
                <div
                  class="up-info__btn-panel"
                  v-if="videoUserInfo.userId !== user.userId"
                >
                  <button
                    class="default-btn follow-btn"
                    :class="
                      videoUserInfo.follow === 1 || videoUserInfo.follow === 2
                        ? 'following'
                        : 'not-follow'
                    "
                    @click="handleFocus"
                  >
                    {{ focusText }}
                  </button>
                </div>
              </div>
            </div>
          </div>
            <section v-if="isVideoAuthor" class="operations-entry">
            <div class="operations-entry-heading"><span class="operations-icon">✦</span><div><strong>天幕运营助手</strong><span>你的内容运营搭档</span></div><small>AI</small></div>
            <p>聊聊观众反馈，一起找到<br>下一个内容灵感。</p>
            <button class="open-assistant" @click="openAssistant">和助手聊聊 <span>↗</span></button>
          </section>
          <!-- 弹幕组件 -->
          <DanmuBox
            :boxHeight="playerSize.height"
            :danmuList="danmuList"
            @seek="(time) => (seekTimePoint = time)"
            @del-danmu="delDanmu"
            @sort-danmu="sortDanmu"
            :sortType="sortType"
          >
          </DanmuBox>
          <!-- 相关视频列表 -->
          <div class="recommend-list">
            <!-- 下一个播放视频 -->
            <div class="next-play" v-if="false">
              <p class="rec-title">
                接下来播放
                <span class="next-button">
                  <span class="txt">自动连播</span>
                  <span class="switch-button" :class="{ on: true }"></span>
                </span>
              </p>

              <!-- <VideoSmallCard
                v-for="item in recmVideoList"
                :key="item.videoId"
                :item="item"
              ></VideoSmallCard> -->
              <!-- 分隔线 -->
              <!-- <div class="split-line"></div> -->
            </div>
            <!-- 推荐视频列表 -->
            <div class="rec-list" v-if="true">
              <div
                v-for="item in recmVideoList"
                :key="item.videoId"
                style="margin-bottom: 12px"
              >
                <!-- 视频卡片 -->
                <VideoSmallCard :item="item"></VideoSmallCard>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <el-drawer v-model="assistantOpen" class="operations-drawer" title="天幕运营助手" append-to-body size="min(1120px, 96vw)" :with-header="false" :close-on-click-modal="false" @closed="assistantClosed">
      <AgentWorkspace v-if="assistantMounted && isVideoAuthor" embedded :video-id="String($route.params.videoId)" :video-title="videoDetail.title || ''" @close="assistantOpen = false" />
    </el-drawer>
  </div>
</template>

<script>
import CommentIndex from "@/components/Comment/CommentIndex.vue";
import HeaderBar from "@/components/headerBar/HeaderBar.vue";
import Artplayer from "@/components/player/Artplayer.vue";
import VPopover from "@/components/popover/VPopover.vue";
import VAvatar from "@/components/avatar/VAvatar.vue";
import DanmuBox from "@/components/danmu/DanmuBox.vue";
import VideoSmallCard from "@/components/VideoSmallCard/VideoSmallCard.vue";
import {
  handleNum,
  handleDuration,
  formatStandardDateTime,
  linkify,
} from "@/lib/utils.js";
import { ElMessage } from "element-plus";
import { getDanmuWSUrl } from "@/lib/config";
import { fetchUserInfo } from "@/api/user";

import {
  fetchVideoDetail,
  fetchLikeVideo,
  fetchLikeVideoCancel,
  fetchCollectVideo,
  fetchCollectVideoCancel,
  fetchCoinVideo,
  fetchTripleVideo,
  fetchDelDanmu,
} from "@/api/video";
import { toRaw, defineAsyncComponent } from "vue";
import { useUserStore } from "@/store/user";
import { mapState, mapActions } from "pinia";
import { mediaVideoUrl } from "@/lib/media";
export default {
  name: "VideoDetail",
  components: {
    CommentIndex,
    HeaderBar,
    Artplayer,
    VPopover,
    VAvatar,
    DanmuBox,
    VideoSmallCard,
    AgentWorkspace: defineAsyncComponent(() => import('@/views/agent/AgentWorkspace.vue')),
  },
  data() {
    return {
      socket: null,
      assistantOpen: false,
      assistantMounted: false,
      videoDetail: {}, //视频详细信息
      commentCount: 0, //评论数
      danmuList: [], //弹幕数据
      likeCount: 0, // 点赞数
      coinCount: 0, // 投币数
      favoriteCount: 0, // 收藏数
      isLike: false, // 是否点赞
      isCoin: false, // 是否投币
      isFavorite: false, // 是否收藏
      likeId: "", // 点赞id，点赞后服务器返回，用于取消点赞
      favoriteId: "", // 收藏id，收藏后服务器返回，用于取消收藏
      playerSize: {
        width: 704,
        height: 442,
      },
      onlineUserCount: 0, //在线人数
      seekTimePoint: -1, // 双击弹幕跳转的时间点
      isSelfDanmu: false, //标记websocket传输的弹幕是否自己发的弹幕
      recmVideoList: [], //推荐视频列表
      showAllDesc: true, // 是否展开简介
      descTooLong: false, // 简介太长需要展开
      sortType: 0, // 排序方式 0默认时间升序 1时间降序 2内容升序 3内容降序

      videoUserInfo: {}, //视频用户信息
      // 三连动画变量开始
      isLongPress: false, // 是否长按
      buttons: {
        like: {
          element: null,
          canvas: null,
          progress: 0,
          active: false,
        },
        collect: {
          element: null,
          canvas: null,
          progress: 0,
        },
        coin: {
          element: null,
          canvas: null,
          progress: 0,
        },
      },
      pressTimer: null,
      pressDelay: 500,
      duration: 2000,
      ctxMap: new Map(),
      // 三连动画变量结束
    };
  },
  created() {
    this.getVideoDetail();
  },
  mounted() {
    // 延时100ms，确保组件渲染完成，再连接websocket
    setTimeout(() => {
      // 连接WebSocket
      this.connectWebSocket();
    }, 100);

    // 初始化三连动画变量
    this.initAllinVar();
  },
  beforeUnmount() {
    // 关闭websocket
    this.closeWebSocket();
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin"]),
    isVideoAuthor() {
      return this.isLogin && this.user.userId != null && this.videoDetail.userId != null
        && String(this.videoDetail.videoId) === String(this.$route.params.videoId)
        && String(this.user.userId) === String(this.videoDetail.userId);
    },
    focusText() {
      if (this.videoUserInfo.follow === 2) {
        return "已互粉";
      }
      if (this.videoUserInfo.follow === 1) {
        return "已关注";
      }
      return "+ 关注";
    },
  },
  watch: {
    isVideoAuthor: {
      immediate: true,
      handler(allowed) {
        if (!allowed) {
          this.assistantOpen = false;
          this.assistantMounted = false;
        } else if (this.$route.query.assistant === '1') this.openAssistant();
      },
    },
  },
  methods: {
    mediaVideoUrl,
    openAssistant() {
      if (!this.isVideoAuthor) return;
      this.assistantMounted = true;
      this.assistantOpen = true;
      if (this.$route.query.assistant !== '1') {
        this.$router.replace({ query: { ...this.$route.query, assistant: '1' } });
      }
    },
    assistantClosed() {
      if (this.$route.query.assistant) {
        this.$router.replace({ query: { ...this.$route.query, assistant: undefined } });
      }
    },
    ...mapActions(useUserStore, [
      "logout",
      "updateDialogVisible",
      "handleFocusAction",
      "updateUserCoin",
    ]),
    // 获取视频详细信息
    async getVideoDetail() {
      try {
        const res = await fetchVideoDetail(
          this.user.userId,
          this.$route.params.videoId
        );
        if (res.code === 200) {
          this.videoDetail = res.data.videoDetailsResponse;
          this.commentCount = res.data.videoDetailsResponse.commentCount;
          this.likeCount = res.data.videoDetailsResponse.likeCount;
          this.coinCount = res.data.videoDetailsResponse.coinCount;
          this.favoriteCount = res.data.videoDetailsResponse.favoriteCount;
          this.danmuList = res.data.onlineBulletList.map((item) => ({
            ...item,
            time: item.playbackTime,
          }));
          this.isLike = res.data.tripleActionResponse.likeId ? true : false;
          this.isCoin = res.data.tripleActionResponse.coin;
          this.isFavorite = res.data.tripleActionResponse.favoriteId
            ? true
            : false;
          this.likeId = res.data.tripleActionResponse.likeId;
          this.favoriteId = res.data.tripleActionResponse.favoriteId;

          this.recmVideoList = res.data.videoRecommendListResponse;
          // 判断简介是否太长，如果太长则显示展开按钮
          this.isDescTooLong();

          // 获取视频用户信息，主要是获取关注状态
          this.getVideoUserInfo();
        }
      } catch (error) {
        console.log("获取视频详情失败", error);
      }
    },
    async getVideoUserInfo() {
      try {
        const res = await fetchUserInfo(
          this.user.userId,
          this.videoDetail.userId
        );
        if (res.code === 200) {
          this.videoUserInfo = res.data;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
    addOneComment() {
      this.commentCount++;
    },
    // 删除评论，delCount为删除的评论数
    delOneComment(delCount) {
      this.commentCount -= delCount;
    },
    connectWebSocket() {
      const socketUrl = getDanmuWSUrl(this.$route.params.videoId);
      this.socket = new WebSocket(socketUrl);
      this.socket.onopen = this.handleWSOpen;
      this.socket.onmessage = this.handleWSMessage;
      this.socket.onerror = this.handleWSError;
      this.socket.onclose = this.handleWSClose;
    },
    async closeWebSocket() {
      if (this.socket != null) {
        await this.socket.close();
        this.socket = null;
      }
    },
    handleWSOpen() {
      console.log("WebSocket 连接成功");
    },
    handleWSMessage(event) {
      this.$nextTick(() => {
        const msgData = JSON.parse(event.data);
        console.log("收到消息:", msgData);
        let danmu = {};
        if (msgData.type === 2) {
          danmu = {
            bulletId: msgData.data.bulletId,
            text: msgData.data.text,
            time: msgData.data.playbackTime,
            userId: msgData.data.userId,
          };
        }

        switch (msgData.type) {
          // websocket消息类型为1时，表示是实时在线人数
          case 1:
            this.onlineUserCount = msgData.data;
            break;
          case 2:
            // websocket消息类型为2时，表示是弹幕消息
            this.danmuList.push(danmu);
            // 如果是自己发的弹幕消息，就使用artplayer的弹幕自动滚动功能，自动emit，根据判断发送弹幕是否成功
            if (
              msgData.data.userId &&
              msgData.data.userId === this.user.userId
            ) {
              // 标记是自己发的弹幕消息
              this.isSelfDanmu = true;
              return;
            }

            toRaw(
              this.$refs.artplayerRef.instance
            ).plugins.artplayerPluginDanmuku.emit(danmu);
            // 标记不是自己发的弹幕消息
            this.isSelfDanmu = false;

            break;
          case 3:
            // websocket消息类型为3时，表示是未登录发送弹幕
            ElMessage.warning("请先登录再发送弹幕");
            // 退出登录，清除登录信息
            this.logout();
            break;
        }
      });
    },
    handleWSError(error) {
      console.error("WebSocket 发生错误:", error);
    },
    handleWSClose(event) {
      console.log(`连接关闭，代码: ${event.code}, 原因如下：`);
      console.dir(event);
    },
    // 发弹幕
    sendDanmu(danmu) {
      this.socket.send(JSON.stringify(danmu));
      // console.log("发送弹幕:", JSON.stringify(danmu),this.socket,WebSocket.OPEN);
    },
    // 删除弹幕
    async delDanmu(danmu) {
      try {
        const params = {
          bulletId: danmu.bulletId,
          userId: this.user.userId,
          videoId: this.$route.params.videoId,
          content: danmu.text,
        };
        const res = await fetchDelDanmu(params);
        if (res.code === 200) {
          ElMessage.success("删除弹幕成功");
          let index = this.danmuList.findIndex(
            (item) => item.bulletId === danmu.bulletId
          );
          if (index !== -1) {
            this.danmuList.splice(index, 1);
            // 切换新弹幕库
            toRaw(
              this.$refs.artplayerRef.instance
            ).plugins.artplayerPluginDanmuku.config({
              danmuku: this.danmuList,
            });
            toRaw(
              this.$refs.artplayerRef.instance
            ).plugins.artplayerPluginDanmuku.load();
          }
        }
      } catch (error) {
        ElMessage.error("删除弹幕失败：" + error);
      }
    },
    formatStandardDateTime(createTime) {
      return formatStandardDateTime(createTime);
    },
    // 点赞或取消点赞视频
    async handleGood() {
      try {
        if (this.isLogin) {
          // 判断是否已经点赞，没有则点赞，有则取消点赞
          if (!this.isLike) {
            const res = await fetchLikeVideo(
              this.user.userId,
              this.videoDetail.videoId
            );
            if (res.code === 200) {
              ElMessage.success("点赞成功");
              this.likeId = res.data; //存储点赞id，用于取消点赞
              this.likeCount++;
              this.isLike = true;
            }
          } else {
            const res = await fetchLikeVideoCancel(
              this.likeId,
              this.videoDetail.videoId
            );
            if (res.code === 200) {
              ElMessage.success("取消点赞成功");
              this.likeCount--;
              this.isLike = false;
            }
          }
        } else {
          ElMessage.warning("请先登录");
        }
      } catch (error) {
        ElMessage.warning("点赞失败：" + error);
      }
    },
    // 收藏或取消收藏视频
    async handleCollect() {
      try {
        if (this.isLogin) {
          // 判断是否已经收藏，没有则收藏，有则取消收藏
          if (!this.isFavorite) {
            const res = await fetchCollectVideo(
              this.user.userId,
              this.videoDetail.videoId
            );
            if (res.code === 200) {
              ElMessage.success("收藏成功");
              this.favoriteId = res.data; //存储收藏id，用于取消收藏
              this.favoriteCount++;
              this.isFavorite = true;
            }
          } else {
            const res = await fetchCollectVideoCancel(
              this.favoriteId,
              this.videoDetail.videoId
            );
            if (res.code === 200) {
              ElMessage.success("取消收藏成功");
              this.favoriteCount--;
              this.isFavorite = false;
            }
          }
        } else {
          ElMessage.warning("请先登录");
        }
      } catch (error) {
        ElMessage.warning("收藏失败：" + error);
      }
    },
    // 投币视频
    async handleCoin() {
      try {
        if (this.user.coinCount < 1) {
          return ElMessage.warning("硬币不足");
        }
        if (this.isLogin) {
          // 如果已经投币，则提示已经投币
          if (this.isCoin) {
            return ElMessage.info("对本稿件的投币数已经用完了");
          }
          const res = await fetchCoinVideo(
            this.user.userId,
            this.videoDetail.videoId
          );
          if (res.code === 200) {
            ElMessage.success("投币成功");
            this.coinCount++;
            this.isCoin = true;
            // 用户硬币数减一
            this.updateUserCoin();
          }
        } else {
          ElMessage.warning("请先登录");
        }
      } catch (error) {
        ElMessage.warning(error);
      }
    },
    // 一键三连视频
    async handleTriple() {
      try {
        if (this.user.coinCount < 1) {
          return ElMessage.warning("硬币不足");
        }
        if (this.isLogin) {
          const res = await fetchTripleVideo(
            this.user.userId,
            this.videoDetail.videoId
          );
          if (res.code === 200) {
            ElMessage.success("一键三连成功");
            // 如果没有点赞过，三连成功后，点赞数才加1，返回的数据中的goodId、collectId有值，则更新，分别存储起来用于取消点赞和取消收藏
            if (!this.isLike) {
              this.likeCount++;
              this.likeId = res.data.likeId;
              this.isLike = true;
            }
            if (!this.isFavorite) {
              this.favoriteCount++;
              this.favoriteId = res.data.favoriteId;
              this.isFavorite = true;
            }
            if (!this.isCoin) {
              this.coinCount++;
              this.isCoin = true;
              // 用户硬币数减一
              this.updateUserCoin();
            }
            // 如果三连成功，
            return Promise.resolve(res);
          }
        } else {
          ElMessage.warning("请先登录");
        }
      } catch (error) {
        ElMessage.error("一键三连失败：" + error);
      }
    },
    noPage() {
      ElMessage.warning("该功能暂未开放");
    },

    // 处理关注和取消关注
    async handleFocus() {
      if (!this.isLogin) {
        return ElMessage.warning("请先登录");
      }
      const res = await this.handleFocusAction(
        this.user.userId,
        this.videoDetail.userId,
        this.videoUserInfo.follow
      );
      if (res && res.code === 200) {
        this.getVideoUserInfo();
      }
    },
    // 处理大于1万的数字
    handleNum(number) {
      return handleNum(number);
    },
    handleDuration(duration) {
      return handleDuration(duration);
    },
    // 判断简介长度是否过长需要收起
    isDescTooLong() {
      this.$nextTick(() => {
        const desc = document.querySelector(".basic-desc-info");
        if (desc.clientHeight > 84) {
          this.descTooLong = true;
          this.showAllDesc = false;
        }
      });
    },
    // 处理超链接文本
    handleLinkify(text) {
      return linkify(text);
    },
    sortDanmu(type) {
      this.sortType = type;
      switch (type) {
        case 0:
          // 时间升序
          this.danmuList.sort((a, b) => a.time - b.time);
          break;
        case 1:
          // 时间降序
          this.danmuList.sort((a, b) => b.time - a.time);
          break;
        case 2:
          // 内容升序
          this.danmuList.sort((a, b) => {
            const textA = a.text;
            const textB = b.text;
            return textA.localeCompare(textB, "zh-Hans-CN", {
              sensitivity: "accent",
            });
          });
          break;
        case 3:
          // 内容降序
          this.danmuList.sort((a, b) => {
            const textA = a.text;
            const textB = b.text;
            return textB.localeCompare(textA, "zh-Hans-CN", {
              sensitivity: "accent",
            });
          });
          break;
      }
    },

    // 三连动画方法开始
    // 初始化三连动画变量
    initAllinVar() {
      // 初始化按钮引用
      this.buttons.like.element = this.$refs.likeBtn;
      this.buttons.like.canvas = this.$refs.likeProgress;
      this.buttons.collect.element = this.$refs.collectBtn;
      this.buttons.collect.canvas = this.$refs.collectProgress;
      this.buttons.coin.element = this.$refs.coinBtn;
      this.buttons.coin.canvas = this.$refs.coinProgress;

      // 初始化Canvas上下文
      Object.keys(this.buttons).forEach((key) => {
        const ctx = this.buttons[key].canvas.getContext("2d");
        this.ctxMap.set(key, ctx);
        this.resizeCanvas(this.buttons[key].element, this.buttons[key].canvas);
      });

      // 窗口大小变化时重置Canvas
      window.addEventListener("resize", () => {
        Object.keys(this.buttons).forEach((key) => {
          this.resizeCanvas(
            this.buttons[key].element,
            this.buttons[key].canvas
          );
        });
      });
    },
    resizeCanvas(button, canvas) {
      const rect = button.getBoundingClientRect();
      canvas.width = rect.width * devicePixelRatio;
      canvas.height = rect.height * devicePixelRatio;
      const ctx = canvas.getContext("2d");
      ctx.scale(devicePixelRatio, devicePixelRatio);
    },
    drawProgress(key) {
      const button = this.buttons[key].element;
      const canvas = this.buttons[key].canvas;
      const ctx = this.ctxMap.get(key);
      const progress = this.buttons[key].progress;

      const rect = button.getBoundingClientRect();
      const centerX = rect.width / 2;
      const centerY = rect.height / 2;
      const radius = rect.width / 2 - 5;

      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.beginPath();
      ctx.arc(
        centerX,
        centerY,
        radius,
        -Math.PI / 2,
        -Math.PI / 2 + (progress / 100) * (Math.PI * 2)
      );
      ctx.strokeStyle = key === "like" ? "blue" : "#00AEEC"; // 不同按钮不同颜色
      ctx.lineWidth = 2;
      ctx.lineCap = "round";
      ctx.stroke();
    },
    async startLongPress(key) {
      this.isLongPress = false;
      if (key !== "like") return;

      // 标记激活状态
      this.buttons.like.active = true;
      this.pressTimer = setTimeout(async () => {
        // 标记长按状态
        this.isLongPress = true;
        // 如果已经三连，长按直接返回
        if (this.isLike && this.isFavorite && this.isCoin) {
          return;
        }

        // 中途取消长按直接返回
        if (!this.buttons.like.active) return;

        this.buttons.like.element.classList.add("shake");
        ["collect", "coin"].forEach((k) => {
          this.buttons[k].canvas.style.display = "block";
        });

        try {
          await Promise.all([
            this.startProgress("collect"),
            this.startProgress("coin"),
          ]);
          // 仅当仍处于激活状态时触发
          if (this.buttons.like.active) {
            // this.triggerScaleAnimation();
            // 进度条完成后向后端发起网络请求
            const res = await this.handleTriple();
            if (res.code === 200) {
              // 请求接口成功后再执行放大动画
              this.triggerScaleAnimation();
            }
          }
        } catch (error) {
          ElMessage.error("三连失败:" + error);
        } finally {
          // 无论成功与否都执行清理震动效果和隐藏进度条
          this.endLongPress("like");
        }
      }, this.pressDelay);
    },
    endLongPress(key) {
      if (key !== "like") return;

      clearTimeout(this.pressTimer);
      // 清除激活状态[1](@ref)
      this.buttons.like.active = false;
      this.buttons.like.element.classList.remove("shake");
      Object.keys(this.buttons).forEach((k) => {
        this.buttons[k].canvas.style.display = "none";
        this.buttons[k].progress = 0;
        this.drawProgress(k);
      });
      // 鼠标弹起时若不是长按状态则执行普通点击事件，点赞或取消点赞
      if (!this.isLongPress) {
        this.handleGood();
      }
    },
    startProgress(key) {
      return new Promise((resolve) => {
        let start = null;
        const animate = (timestamp) => {
          // 检查激活状态[1](@ref)
          if (!this.buttons.like.active) {
            resolve();
            return;
          }

          if (!start) start = timestamp;
          const progress = Math.min(
            ((timestamp - start) / this.duration) * 100,
            100
          );

          this.buttons[key].progress = progress;
          this.drawProgress(key);

          progress < 100 ? requestAnimationFrame(animate) : resolve();
        };
        requestAnimationFrame(animate);
      });
    },
    triggerScaleAnimation() {
      // 执行放大动画
      const animations = Object.keys(this.buttons).map((key) => {
        return new Promise((resolve) => {
          const btn = this.buttons[key].element;
          btn.classList.add("scale-animation");
          btn.addEventListener(
            "animationend",
            () => {
              btn.classList.remove("scale-animation");
              resolve();
            },
            { once: true }
          );
        });
      });

      // 所有动画完成后重置状态
      Promise.all(animations).then(() => {
        this.endLongPress("like");
      });
    },
    // 三连动画方法结束
  },
};
</script>

<style scoped>
.operations-entry { margin: 0 0 18px; padding: 21px; border: 1px solid #f0e1ed; border-radius: 14px; background: radial-gradient(ellipse at top right, #f4e7f9, transparent 65%), #fff8fb; }
.operations-entry-heading { display: flex; align-items: center; gap: 10px; }
.operations-icon { width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid #eddaeb; border-radius: 11px; background: #fff7fc; color: #c583a9; font-size: 27px; }
.operations-entry-heading strong { display: block; color: #5b465d; font-size: 14px; font-weight: 600; }
.operations-entry-heading div > span { display: block; font-size: 10px; color: #b39aac; margin-top: 3px; }
.operations-entry-heading small { margin-left: auto; font-size: 9px; letter-spacing: 1px; color: #be9eb5; background: #fff8fd; border: 1px solid #eadce9; border-radius: 4px; padding: 2px 5px; }
.operations-entry p { color: #aa8fa3; font-size: 12px; line-height: 1.8; margin: 15px 0; }
.open-assistant { display: flex; align-items: center; justify-content: space-between; width: 100%; padding: 10px 13px; border: 1px solid #e9cee0; border-radius: 8px; background: #fff; color: #b5789a; font-size: 12px; cursor: pointer; transition: background .2s; }
.open-assistant:hover { background: #f9edf4; }
.video-container {
  width: auto;
  padding: 64px 10px 0px;
  max-width: 2540px;
  min-width: 1080px;
  margin: 0 auto;
  display: flex;
  justify-content: center;
  box-sizing: content-box;
  position: relative;
}

.left-container {
  /*position: sticky;*/
  height: fit-content;
}

.video-info-container {
  height: 104px;
  box-sizing: border-box;
  padding-top: 22px;
}

.video-title {
  font-size: 20px;
  font-weight: 500;
  -webkit-font-smoothing: antialiased;
  color: var(--text1);
  line-height: 28px;
  margin-bottom: 6px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.video-info-detail {
  font-size: 13px;
  color: var(--text3);
  display: flex;
  align-items: center;
  height: 24px;
  line-height: 18px;
  position: relative;
  overflow: hidden;
}

.video-info-detail-list {
  display: flex;
  align-items: center;
  overflow: hidden;
  box-sizing: border-box;
}

.video-info-detail-list .item {
  flex-shrink: 0;
  margin-right: 12px;
  overflow: hidden;
}

.video-info-detail-list .item:last-child {
  margin-right: 0;
}

.view,
.danmu,
.copyright {
  display: inline-flex;
  align-items: center;
}

.icon-bofangshu .icon-danmushu {
  font-size: 18px;
}
.date {
  text-overflow: ellipsis;
  overflow: hidden;
  white-space: nowrap;
  line-height: 24px;
  font-size: 13px;
  height: 100%;
  display: inline-block;
  vertical-align: middle;
}

.icon-jinzhi {
  font-size: 12px;
  margin-right: 4px;
  color: var(--stress_red);
}

.video-toolbar-container {
  display: flex;
  align-items: center;
  justify-content: space-between;
  /* padding-top: 16px;
  padding-bottom: 12px; */
  padding: 5px 0;
  line-height: 28px;
  border-bottom: 1px solid var(--line_regular);
}

.video-toolbar-left {
  position: relative;
  display: flex;
  align-items: center;
  -webkit-user-select: none;
  user-select: none;
}

.toolbar-left-item-wrap {
  position: relative;
  margin-right: 8px;
}

.video-toolbar-left-item {
  position: relative;
  display: -ms-flexbox;
  display: flex;
  -ms-flex-align: center;
  align-items: center;
  width: 92px;
  white-space: nowrap;
  transition: all 0.3s;
  font-size: 13px;
  color: var(--text2);
  font-weight: 500;
  cursor: pointer;
}

.video-toolbar-left-item.on,
.video-toolbar-left-item:hover {
  color: var(--brand_blue);
}

.video-toolbar-left-item .iconfont {
  margin-right: 8px;
  font-size: 26px;
}

.video-toolbar-left-item .icon-diancai {
  transform: translateY(2px);
}

.video-toolbar-item-text {
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: break-word;
  white-space: nowrap;
}

.dianzan-gif {
  position: absolute;
  top: -50px;
  left: -10px;
  height: 40px;
}

.dianzan-gif img {
  height: 100%;
}

.gif-hide {
  animation: disappear 0.2s ease-out forwards;
  transform-origin: bottom;
}

.gif-show {
  animation: appear 0.2s ease-out forwards;
  transform-origin: bottom;
}

@keyframes appear {
  0% {
    opacity: 0;
    transform: translateY(5px) scale(0);
  }

  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes disappear {
  0% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }

  100% {
    opacity: 0;
    transform: translateY(5px) scale(0);
  }
}

.icon-jubao1 {
  margin-right: 10px;
}

.video-desc-container {
  margin: 16px 0;
}

.basic-desc-info {
  white-space: pre-line;
  letter-spacing: 0;
  color: var(--text1);
  font-size: 15px;
  line-height: 24px;
  overflow: hidden;
}

.toggle-btn {
  margin-top: 10px;
  font-size: 13px;
  line-height: 18px;
}

.toggle-btn-text {
  cursor: pointer;
  color: var(--text2);
}

.toggle-btn-text:hover {
  color: var(--brand_blue);
}

.video-tag-container {
  padding-bottom: 6px;
  margin: 16px 0 20px 0;
  border-bottom: 1px solid var(--line_regular);
  display: flex;
  flex-wrap: wrap;
}

.tag-container {
  margin: 0px 12px 8px 0;
}

.tag-link {
  color: var(--text2);
  background: var(--graph_bg_regular);
  height: 28px;
  line-height: 28px;
  border-radius: 14px;
  font-size: 13px;
  padding: 0 12px;
  box-sizing: border-box;
  transition: all 0.3s;
  display: -ms-inline-flexbox;
  display: inline-flex;
  -ms-flex-align: center;
  align-items: center;
  cursor: pointer;
}

.right-container {
  width: 350px;
  flex: none;
  margin-left: 30px;
  position: relative;
  pointer-events: none;
}

.right-container-inner {
  padding-bottom: 250px;
  /* position: sticky; */
}

.right-container-inner * {
  pointer-events: all;
}

.up-info-container {
  box-sizing: border-box;
  height: 104px;
  display: flex;
  align-items: center;
}

.up-avatar-wrap {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  align-items: center;
}

.up-avatar {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background-color: var(--graph_weak);
}

.up-info--right {
  margin-left: 12px;
  flex: 1;
}

.up-info__detail {
  margin-bottom: 5px;
}

.up-detail-top {
  display: flex;
  align-items: center;
}

.up-name {
  font-size: 15px;
  color: var(--text1);
  font-weight: 500;
  position: relative;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
  margin-right: 12px;
  max-width: calc(100% - 12px - 56px);
}

.up-description {
  margin-top: 2px;
  font-size: 13px;
  line-height: 16px;
  height: 16px;
  color: var(--text3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.up-info__btn-panel {
  clear: both;
  display: flex;
  margin-top: 5px;
  white-space: nowrap;
}

.up-info__btn-panel .default-btn {
  box-sizing: border-box;
  padding: 0;
  line-height: 30px;
  height: 30px;
  border-radius: 6px;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  /* cursor: pointer; */
  background: var(--graph_weak);
  position: relative;
  transition: 0.3s all;
}

.follow-btn {
  width: 200px;
  cursor: pointer;
}

.follow-btn.following {
  color: var(--text3);
  background-color: var(--graph_bg_thick);
}

.follow-btn.following:hover {
  background-color: var(--graph_bg_regular);
}

.follow-btn.not-follow {
  background: var(--brand_blue);
  color: var(--text_white);
}

.follow-btn.not-follow:hover {
  background: #00b8f6;
}

.follow-btn .iconfont {
  font-size: 14px;
  margin-right: 2px;
}

.recommend-list {
  margin-top: 18px;
}

.rec-title {
  font-size: 15px;
  -webkit-font-smoothing: antialiased;
  color: var(--text1);
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  line-height: 20px;
}

.next-button {
  color: var(--text3);
  font-size: 13px;
  line-height: 16px;
  cursor: pointer;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  user-select: none;
}

.next-button .txt {
  margin-right: 4px;
  vertical-align: middle;
}

.next-button .switch-button {
  margin: 0;
  display: inline-block;
  position: relative;
  width: 30px;
  height: 20px;
  outline: none;
  border-radius: 10px;
  box-sizing: border-box;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
  vertical-align: middle;
  background: var(--text3);
  border: 1px solid var(--text3);
}

.next-button .switch-button.on {
  background: var(--brand_blue);
  border: 1px solid var(--brand_blue);
}

.next-button .switch-button:after {
  content: "";
  position: absolute;
  top: 1px;
  left: 1px;
  border-radius: 100%;
  width: 16px;
  height: 16px;
  background-color: #fff;
  transition: all 0.2s;
}

.next-button .switch-button.on:after {
  left: 11px;
}

.split-line {
  width: 100%;
  height: 1px;
  background: var(--line_regular);
}

.rec-list {
  margin-top: 18px;
}

::v-deep .art-video-player {
  width: 100% !important;
}
@media (min-width: 1681px) {
  .video-info-container {
    height: 108px;
  }

  .up-info-container {
    height: 108px;
  }

  .video-info-container .video-title {
    font-size: 22px;
    line-height: 34px;
  }

  .right-container {
    width: 411px;
  }

  .up-name {
    font-size: 16px;
    max-width: calc(100% - 12px - 60px);
  }

  .up-description {
    font-size: 14px;
  }

  .follow-btn {
    width: 230px;
  }
}

/* 三连动画样式开始 */
@keyframes scaleUp {
  0%,
  100% {
    transform: scale(1);
  }

  50% {
    transform: scale(1.2);
  }
}

.scale-animation {
  animation: scaleUp 0.5s ease-in-out;
}

.container {
  display: flex;
  gap: 40px;
  align-items: center;
  justify-content: center;
  height: 100vh;
}

.btn {
  width: 50px;
  height: 50px;
  /* background-color: #ff6699; */
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30px;
  cursor: pointer;
  position: relative;
  user-select: none;
}

/* 点赞按钮专用动画 */
.btn.shake {
  animation: shake 1.5s ease-in-out infinite;
}

.progress-ring {
  position: absolute;
  width: 100%;
  height: 100%;
  pointer-events: none;
  display: none;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

/* @keyframes 用于点赞动画 */
@keyframes shake {
  2% {
    transform: translate(0.5px, -0.5px) rotate(0.5deg);
  }

  4% {
    transform: translate(-0.5px, 2.5px) rotate(0.5deg);
  }

  6% {
    transform: translate(-1.5px, 2.5px) rotate(-0.5deg);
  }

  8% {
    transform: translate(-1.5px, 0.5px) rotate(1.5deg);
  }

  10% {
    transform: translate(1.5px, 2.5px) rotate(0.5deg);
  }

  12% {
    transform: translate(-1.5px, 2.5px) rotate(1.5deg);
  }

  14% {
    transform: translate(1.5px, 1.5px) rotate(-0.5deg);
  }

  16% {
    transform: translate(2.5px, -0.5px) rotate(1.5deg);
  }

  18% {
    transform: translate(1.5px, 0.5px) rotate(0.5deg);
  }

  20% {
    transform: translate(1.5px, -1.5px) rotate(-0.5deg);
  }

  22% {
    transform: translate(-1.5px, -1.5px) rotate(-0.5deg);
  }

  24% {
    transform: translate(-0.5px, -1.5px) rotate(0.5deg);
  }

  26% {
    transform: translate(-1.5px, 2.5px) rotate(-0.5deg);
  }

  28% {
    transform: translate(2.5px, 1.5px) rotate(1.5deg);
  }

  30% {
    transform: translate(0.5px, -0.5px) rotate(1.5deg);
  }

  32% {
    transform: translate(1.5px, 2.5px) rotate(1.5deg);
  }

  34% {
    transform: translate(-1.5px, -1.5px) rotate(-0.5deg);
  }

  36% {
    transform: translate(-0.5px, 0.5px) rotate(-0.5deg);
  }

  38% {
    transform: translate(1.5px, -1.5px) rotate(1.5deg);
  }

  40% {
    transform: translate(-1.5px, 0.5px) rotate(1.5deg);
  }

  42% {
    transform: translate(2.5px, -0.5px) rotate(1.5deg);
  }

  44% {
    transform: translate(-1.5px, 2.5px) rotate(0.5deg);
  }

  46% {
    transform: translate(-0.5px, 2.5px) rotate(-0.5deg);
  }

  48% {
    transform: translate(1.5px, -1.5px) rotate(1.5deg);
  }

  50% {
    transform: translate(1.5px, -0.5px) rotate(1.5deg);
  }

  52% {
    transform: translate(0.5px, 0.5px) rotate(-0.5deg);
  }

  54% {
    transform: translate(-1.5px, -1.5px) rotate(-0.5deg);
  }

  56% {
    transform: translate(1.5px, -1.5px) rotate(1.5deg);
  }

  58% {
    transform: translate(0.5px, 1.5px) rotate(1.5deg);
  }

  60% {
    transform: translate(-0.5px, -0.5px) rotate(1.5deg);
  }

  62% {
    transform: translate(-1.5px, 0.5px) rotate(1.5deg);
  }

  64% {
    transform: translate(0.5px, -0.5px) rotate(1.5deg);
  }

  66% {
    transform: translate(2.5px, 0.5px) rotate(1.5deg);
  }

  68% {
    transform: translate(1.5px, -0.5px) rotate(1.5deg);
  }

  70% {
    transform: translate(0.5px, 2.5px) rotate(1.5deg);
  }

  72% {
    transform: translate(1.5px, -0.5px) rotate(0.5deg);
  }

  74% {
    transform: translate(2.5px, -0.5px) rotate(0.5deg);
  }

  76% {
    transform: translate(2.5px, -0.5px) rotate(1.5deg);
  }

  78% {
    transform: translate(-1.5px, -1.5px) rotate(-0.5deg);
  }

  80% {
    transform: translate(-1.5px, 0.5px) rotate(-0.5deg);
  }

  82% {
    transform: translate(-1.5px, 2.5px) rotate(0.5deg);
  }

  84% {
    transform: translate(-0.5px, 0.5px) rotate(1.5deg);
  }

  86% {
    transform: translate(-1.5px, 1.5px) rotate(0.5deg);
  }

  88% {
    transform: translate(-1.5px, -1.5px) rotate(1.5deg);
  }

  90% {
    transform: translate(-1.5px, -1.5px) rotate(1.5deg);
  }

  92% {
    transform: translate(2.5px, 1.5px) rotate(0.5deg);
  }

  94% {
    transform: translate(2.5px, 1.5px) rotate(-0.5deg);
  }

  96% {
    transform: translate(2.5px, -0.5px) rotate(0.5deg);
  }

  98% {
    transform: translate(1.5px, 0.5px) rotate(-0.5deg);
  }

  0%,
  to {
    transform: translate(0) rotate(0);
  }
}

/* 三连动画样式结束 */
</style>

<style>
.operations-drawer.el-drawer { border-radius: 22px 0 0 22px; overflow: hidden; }
.operations-drawer .el-drawer__body { padding: 0; overflow: hidden; }
@media (max-width: 760px) { .operations-drawer.el-drawer { border-radius: 16px 0 0 16px; } }
</style>

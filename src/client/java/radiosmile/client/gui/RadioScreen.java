package radiosmile.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import radiosmile.client.radio.ClientRadioData;
import radiosmile.client.radio.ClientRadioNetwork;
import radiosmile.client.radio.ClientRadioPlayback;
import radiosmile.radio.RadioStation;

/**
 * 收音机界面。
 *
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │              [ 搜索框 ]                       │
 *   │  ┌────────────────────────────────┐   ┌──┐   │
 *   │  │ 电台列表（可滚动）              │   │音│   │
 *   │  │  · 频道 A                      │   │量│   │
 *   │  │  · 频道 B                      │   │  │   │
 *   │  └────────────────────────────────┘   └──┘   │
 *   │   [退出]        [扫描]        [确认]          │
 *   └──────────────────────────────────────────────┘
 * </pre>
 */
public class RadioScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int ROW_STEP = ROW_HEIGHT + 2;
	private static final int SCROLLBAR_WIDTH = 5;
	/** 列表请求节流：避免界面重建时反复打网络。 */
	private static long lastRequestMillis;
	/** 音量同步节流间隔（毫秒）。 */
	private static final long VOLUME_SYNC_INTERVAL_MS = 100L;
	/** 音量行程档数：10 档（0%、10%、20% … 100%）。 */
	private static final int VOLUME_STEPS = 10;
	/** 上次音量同步时间。 */
	private long lastVolumeSyncAt;
	/** 是否有因节流而未发出的音量变更。 */
	private boolean pendingVolumeSync;

	/**
	 * 本界面当前显示的音量（0~1）。
	 *
	 * <p><b>这是这份 UI 的权威值</b>，不由 {@code PcmSink.volumeFor()} 逐帧反查。
	 * 因为混音器的音量缓存会被 {@code stop()} 清掉（点扫描会 stop 再重连），
	 * 逐帧反查会导致音量条在无关操作时自己跳变。
	 *
	 * <p>维护点：{@link #init()} 读方块 NBT 初始化；拖动时更新；确认时兜底同步。
	 */
	private float currentVolume = 1.0F;

	private final List<RadioStation> shown = new ArrayList<>();
	private EditBox searchBox;
	private Button exitButton;
	private Button scanButton;
	private Button confirmButton;

	private String selectedId = "";
	private int scroll;
	private int listLeft;
	private int listRight;
	private int listTop;
	private int listBottom;
	private int volumeLeft;
	private int volumeRight;
	private int volumeTop;
	private int volumeBottom;
	private boolean draggingVolume;

	private final net.minecraft.core.BlockPos blockPos;

	/**
	 * 打开界面时该方块原本的频道。
	 *
	 * <p>用于判断「用户到底有没有换台」：确认时如果选择与原频道一致，
	 * 就什么都不做，避免无谓地拆掉正在播的流重连（网络流重连要 1 秒）。
	 */
	private final String originalStationId;

	/**
	 * @param currentStationId 方块当前保存的频道 id；进入界面时默认高亮该行。
	 *                         为空则回退到客户端缓存的当前播放频道。
	 * @param blockPos         打开此界面的收音机方块坐标，确认时随包发回服务端写入 NBT。
	 */
	public RadioScreen(String currentStationId, net.minecraft.core.BlockPos blockPos) {
		super(Component.translatable("gui.radio-smile.radio.title"));
		this.selectedId = currentStationId == null ? "" : currentStationId;
		this.originalStationId = this.selectedId;
		this.blockPos = blockPos;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;

		this.searchBox = new EditBox(this.font, centerX - 110, 26, 220, 18,
				Component.translatable("gui.radio-smile.search"));
		this.searchBox.setMaxLength(64);
		this.searchBox.setHint(Component.translatable("gui.radio-smile.search"));
		this.searchBox.setResponder(value -> {
			this.scroll = 0;
			this.rebuild();
		});
		this.addRenderableWidget(this.searchBox);

		this.volumeRight = centerX + 170;
		this.volumeLeft = this.volumeRight - 18;
		this.volumeTop = 52;
		this.volumeBottom = Math.max(this.volumeTop + 60, this.height - 50);

		this.listLeft = centerX - 170;
		this.listRight = this.volumeLeft - 10;
		this.listTop = 52;
		this.listBottom = this.volumeBottom;

		int buttonY = this.height - 30;
		int gap = 6;
		int listWidth = this.listRight - this.listLeft;
		// 三个按钮：退出 / 扫描 / 确认
		// 「重连」已并入「扫描」——见 onScan()
		int each = (listWidth - gap * 2) / 3;

		this.exitButton = Button.builder(Component.translatable("gui.radio-smile.exit"),
				b -> this.onClose())
				.bounds(this.listLeft, buttonY, each, 20).build();
		this.scanButton = Button.builder(Component.translatable("gui.radio-smile.scan"),
				b -> this.onScan())
				.bounds(this.listLeft + (each + gap), buttonY, each, 20).build();
		this.confirmButton = Button.builder(Component.translatable("gui.radio-smile.confirm"),
				b -> this.onConfirm())
				.bounds(this.listLeft + (each + gap) * 2, buttonY, each, 20).build();

		this.addRenderableWidget(this.exitButton);
		this.addRenderableWidget(this.scanButton);
		this.addRenderableWidget(this.confirmButton);

		// 频道完全取自本方块：方块没设频道就是空置。
		// 不能回退到「客户端正在播放的频道」——那会让新方块显示上一个方块的选择。
		if (this.selectedId == null) {
			this.selectedId = "";
		}

		// 音量优先取方块实体 NBT 里的权威值（跨客户端一致），
		// 其次取本机缓存，都没有则 100%。绝不回退到其它方块的值。
		float own = 1.0F;
		if (this.blockPos != null) {
			boolean fromBlock = false;
			var level = net.minecraft.client.Minecraft.getInstance().level;
			if (level != null
					&& level.getBlockEntity(this.blockPos)
					instanceof radiosmile.block.entity.RadioBlockEntity be) {
				own = be.getVolume();
				fromBlock = true;
			}
			if (!fromBlock) {
				own = radiosmile.client.audio.PcmSink.INSTANCE.volumeFor(this.blockPos.asLong());
			}
			// 同步到本机缓存，供混音使用
			radiosmile.client.audio.PcmSink.INSTANCE.setVolumeFor(this.blockPos.asLong(), own);
		}
		// 界面自己保存一份：之后音量条只认这个值，不再逐帧反查混音器缓存
		this.currentVolume = own;
		ClientRadioPlayback.setBlockVolume(own);

		this.rebuild();
		this.requestStationsThrottled();
		this.ensureSelectedVisible();
	}

	/** 让当前选中项在滚动区域内可见（进入界面时默认高亮并滚动到它）。 */
	private void ensureSelectedVisible() {
		int index = indexOf(this.selectedId);
		if (index < 0) {
			return;
		}
		int rowTop = index * ROW_STEP;
		int view = this.listBottom - this.listTop;
		if (rowTop < this.scroll) {
			this.scroll = rowTop;
		} else if (rowTop + ROW_HEIGHT > this.scroll + view) {
			this.scroll = rowTop + ROW_HEIGHT - view;
		}
		this.scroll = clampScroll(this.scroll);
	}

	private int indexOf(String id) {
		if (id == null || id.isBlank()) {
			return -1;
		}
		for (int i = 0; i < this.shown.size(); i++) {
			if (id.equals(this.shown.get(i).id())) {
				return i;
			}
		}
		return -1;
	}

	/** 节流请求：短时间内重复重建界面时不重复打网络。 */
	private void requestStationsThrottled() {
		long now = System.currentTimeMillis();
		if (now - lastRequestMillis < 500L) {
			return;
		}
		lastRequestMillis = now;
		ClientRadioNetwork.requestStations();
	}

	@Override
	public void tick() {
		super.tick();
		if (this.searchBox != null) {
			this.searchBox.tick();
		}
		if (this.shown.size() != ClientRadioData.stations().size()
				|| (this.shown.isEmpty() && ClientRadioData.isLoaded())) {
			this.rebuild();
		}
	}

	/**
	 * 「扫描」按钮：只刷新电台列表，<b>正常播放时绝不重订阅</b>。
	 *
	 * <h2>为什么不在扫描里重连</h2>
	 * 曾经把重连并进扫描，想当然地认为「刷新 = 重新问一遍状态」。
	 * 但重订阅对<b>正在正常播放</b>的流是纯粹的伤害：
	 *
	 * <ol>
	 *   <li><b>网络直播流会拿到上游缓存</b>：每次重连都是全新 HTTP 连接，
	 *       上游从它自己的编码缓冲处开始发，于是重连后播放的是几秒前的旧内容，
	 *       与刚刚断开的位置对不上，听感是明显的<b>割裂</b>。</li>
	 *   <li><b>必然卡顿</b>：旧连接的抖动缓冲被清空，新连接又要重新攒到起播门槛，
	 *       中间那段空窗就是可听见的停顿。</li>
	 *   <li><b>本地文件也会跳</b>：重新 openSource() 会按时间轴重新定位，
	 *       虽然能对上，但仍然是一次无谓的播放中断。</li>
	 * </ol>
	 *
	 * 所以这里只做「刷新列表」这一件名正言顺的事。
	 * 真正需要重连的情况——播放失败、有错误码/超时——由
	 * {@code AutoSubscriber} 的失败检测负责，那里会判断确认失败后才重订阅。
	 */
	private void onScan() {
		// 刷新列表与计时基准（无节流：用户主动要求刷新）
		lastRequestMillis = 0L;
		ClientRadioNetwork.requestStations();
	}

	/** 确认：把选择发给服务端（含方块坐标，用于写入 NBT），并自动关闭界面。 */
	/**
	 * 确认。
	 *
	 * <p>选中了频道就同步给服务端并开始拉流；若选择被清空（再次点击已选中的项），
	 * 则视为「不播任何台」：停掉该方块的播放并清空 NBT 里的频道。
	 */
	private void onConfirm() {
		boolean hasSelection = this.selectedId != null && !this.selectedId.isBlank();
		if (this.blockPos != null) {
			if (hasSelection) {
				// 频道没变就【什么都不做】，尤其不要重新订阅。
				//
				// 早期无论是否换台都无条件 subscribeAudio：服务端会先 unsubscribe()
				// 再重新 openSource()，等于把正在播的流整条拆掉重建——
				// 本地文件是重新 seek（听感是跳一下），网络流则要【重新连接上游】，
				// 实测建连约 1 秒，这段空隙还会触发调台噪。
				// 用户只是点了下确认、什么都没改，就被中断一次，完全是多余的。
				boolean stationChanged = !this.selectedId.equals(this.originalStationId);
				if (!stationChanged) {
					// 选择与原频道一致：直接关界面，不打扰播放
					this.onClose();
					return;
				}
				ClientRadioNetwork.selectStation(this.selectedId, this.blockPos);
				ClientRadioNetwork.requestIcon(this.selectedId);
				// 换台了才立即开始拉流，不必等再次右键
				ClientRadioNetwork.subscribeAudio(this.blockPos, this.selectedId);
			} else {
				// 空置：停止播放并清空方块记录的频道
				ClientRadioNetwork.unsubscribeAudio(this.blockPos);
				ClientRadioNetwork.clearStation(this.blockPos);
				radiosmile.client.audio.ClientAudioPlayer.stop(this.blockPos);
			}
		}
		this.onClose();
	}

	private void rebuild() {
		String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
		List<RadioStation> result = new ArrayList<>();
		for (RadioStation station : ClientRadioData.stations()) {
			if (query.isEmpty() || contains(station.name(), query) || contains(station.id(), query)) {
				result.add(station);
			}
		}
		this.shown.clear();
		this.shown.addAll(result);
		this.scroll = clampScroll(this.scroll);
	}

	private static boolean contains(String value, String query) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(query);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

		this.renderList(graphics, mouseX, mouseY);
		this.renderVolume(graphics);

		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.fill(this.listLeft - 2, this.listTop - 2, this.listRight + 2, this.listBottom + 2, 0x66000000);

		if (this.shown.isEmpty()) {
			Component empty = ClientRadioData.isLoaded()
					? Component.translatable("gui.radio-smile.empty")
					: Component.translatable("gui.radio-smile.loading");
			graphics.drawCenteredString(this.font, empty,
					(this.listLeft + this.listRight) / 2, (this.listTop + this.listBottom) / 2 - 4, 0xA0A0A0);
			this.renderScrollbar(graphics);
			return;
		}

		graphics.enableScissor(this.listLeft, this.listTop, this.listRight, this.listBottom);
		int rowTop = this.listTop - this.scroll;
		for (int i = 0; i < this.shown.size(); i++) {
			int top = rowTop + i * ROW_STEP;
			if (top + ROW_HEIGHT < this.listTop || top > this.listBottom) {
				continue;
			}
			RadioStation station = this.shown.get(i);
			boolean selected = station.id().equals(this.selectedId);
			boolean hoveredRow = mouseX >= this.listLeft && mouseX <= this.listRight
					&& mouseY >= top && mouseY < top + ROW_HEIGHT;
			if (selected) {
				graphics.fill(this.listLeft, top, this.listRight, top + ROW_HEIGHT, 0x884C8F4C);
				graphics.fill(this.listLeft, top, this.listLeft + 2, top + ROW_HEIGHT, 0xFF9BE09B);
			} else if (hoveredRow) {
				graphics.fill(this.listLeft, top, this.listRight, top + ROW_HEIGHT, 0x33FFFFFF);
			}

			int textY = top + (ROW_HEIGHT - 8) / 2;
			int nameX = this.listLeft + 8;
			ResourceLocation icon = ClientRadioData.iconOf(station.id());
			if (icon != null) {
				graphics.blit(icon, nameX, top + 2, 0, 0, 16, 16, 16, 16);
				nameX += 20;
			}

			String name = station.name() == null || station.name().isBlank() ? station.id() : station.name();
			graphics.drawString(this.font, this.font.plainSubstrByWidth(name, this.listRight - nameX - 8),
					nameX, textY, selected ? 0xFFFFFFFF : 0xFFE6E6E6, false);
		}
		graphics.disableScissor();
		this.renderScrollbar(graphics);
	}

	private void renderScrollbar(GuiGraphics graphics) {
		int content = this.shown.size() * ROW_STEP;
		int view = this.listBottom - this.listTop;
		if (content <= view || view <= 0) {
			return;
		}
		int trackX = this.listRight - SCROLLBAR_WIDTH;
		graphics.fill(trackX, this.listTop, this.listRight, this.listBottom, 0x33FFFFFF);
		int thumbHeight = Math.max(12, view * view / content);
		int travel = view - thumbHeight;
		int offset = travel * this.scroll / Math.max(1, content - view);
		graphics.fill(trackX, this.listTop + offset, this.listRight, this.listTop + offset + thumbHeight, 0xFFB0B0B0);
	}

	private void renderVolume(GuiGraphics graphics) {
		graphics.fill(this.volumeLeft - 2, this.volumeTop - 2,
				this.volumeRight + 2, this.volumeBottom + 2, 0x66000000);

		int trackTop = this.volumeTop + 12;
		int trackBottom = this.volumeBottom - 12;
		int trackX = (this.volumeLeft + this.volumeRight) / 2 - 2;
		graphics.fill(trackX, trackTop, trackX + 4, trackBottom, 0x55FFFFFF);

		// <b>用界面自己保存的音量值</b>，不要每次渲染都去混音器（PcmSink）查。
		//
		// 原因：点「扫描」会先 stop() 再重订阅，而 stop() 里的
		// clearVolumeFor() 会删掉该方块的音量记录，volumeFor() 于是回退到默认 1.0。
		// 若界面每帧都去查它，音量条就会在点扫描的瞬间跳到 100%——
		// 玩家看到的是「我没动音量，条自己跳了」。
		//
		// 界面里的 {@link #currentVolume} 才是这份 UI 的权威值：
		// 它在 init() 时从方块 NBT 读一次，之后由拖动/确认维护。
		float volume = this.currentVolume;
		int trackHeight = trackBottom - trackTop;
		// 与 5 档行程对齐，让滑块位置与实际音量一致
		int filled = Math.round(trackHeight * volume);
		graphics.fill(trackX, trackBottom - filled, trackX + 4, trackBottom, 0xFF7ED97E);

		int knobY = trackBottom - filled;
		graphics.fill(this.volumeLeft + 2, knobY - 2, this.volumeRight - 2, knobY + 2, 0xFFE0E0E0);

		graphics.drawCenteredString(this.font, Component.translatable("gui.radio-smile.volume"),
				(this.volumeLeft + this.volumeRight) / 2, this.volumeTop + 1, 0xFFFFFF);
		graphics.drawCenteredString(this.font, Math.round(volume * 100) + "%",
				(this.volumeLeft + this.volumeRight) / 2, this.volumeBottom - 10, 0xC0C0C0);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (mouseX >= this.volumeLeft - 2 && mouseX <= this.volumeRight + 2
					&& mouseY >= this.volumeTop && mouseY <= this.volumeBottom) {
				this.draggingVolume = true;
				this.updateVolumeFromMouse(mouseY);
				return true;
			}
			if (mouseX >= this.listLeft && mouseX <= this.listRight
					&& mouseY >= this.listTop && mouseY <= this.listBottom
					&& !this.shown.isEmpty()) {
				int index = (int) ((mouseY - this.listTop + this.scroll) / ROW_STEP);
				if (index >= 0 && index < this.shown.size()) {
					String clicked = this.shown.get(index).id();
					// 再次点击已选中的项 = 取消选择（空置），便于「不选任何台」
					this.selectedId = clicked.equals(this.selectedId) ? "" : clicked;
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/**
	 * 键盘操作：上下方向键移动选择，Enter/小键盘 Enter 确认。
	 * 搜索框获得焦点时把按键让给输入框。
	 */
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.searchBox != null && this.searchBox.isFocused()) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (this.shown.isEmpty()) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		switch (keyCode) {
			case 265 -> { // UP
				this.moveSelection(-1);
				return true;
			}
			case 264 -> { // DOWN
				this.moveSelection(1);
				return true;
			}
			case 257, 335 -> { // ENTER, KP_ENTER
				this.onConfirm();
				return true;
			}
			default -> {
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
		}
	}

	/** 在当前列表内上下移动选择，并保证可见。 */
	private void moveSelection(int delta) {
		if (this.shown.isEmpty()) {
			return;
		}
		int index = indexOf(this.selectedId);
		if (index < 0) {
			index = delta > 0 ? 0 : this.shown.size() - 1;
		} else {
			index = Math.floorMod(index + delta, this.shown.size());
		}
		this.selectedId = this.shown.get(index).id();
		this.ensureSelectedVisible();
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && this.draggingVolume) {
			this.draggingVolume = false;
			// 松手时补发最终音量，保证服务端拿到准确值
			this.flushVolumeSync();
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (this.draggingVolume && button == 0) {
			this.updateVolumeFromMouse(mouseY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	/**
	 * 从鼠标位置换算音量并<b>立即生效</b>。
	 *
	 * <p>拖动时不等「确认」：
	 * <ol>
	 *   <li>先本地即时应用（改声卡增益），听感无延迟</li>
	 *   <li>再发给服务端写回方块 NBT，但做节流——拖动一次会产生几十个事件，
	 *       全发会白占带宽；这里限制为每 {@value #VOLUME_SYNC_INTERVAL_MS} 毫秒最多一次，
	 *       并始终在松手时补发最终值（见 {@link #mouseReleased}）</li>
	 * </ol>
	 */
	private void updateVolumeFromMouse(double mouseY) {
		int trackTop = this.volumeTop + 12;
		int trackBottom = this.volumeBottom - 12;
		int trackHeight = trackBottom - trackTop;
		if (trackHeight <= 0) {
			return;
		}
		float raw = (float) ((trackBottom - mouseY) / trackHeight);
		raw = Math.max(0.0F, Math.min(1.0F, raw));

		// 音量行程分 10 档（每档 10%），避免连续值在拖动时抖动
		float volume = Math.round(raw * VOLUME_STEPS) / (float) VOLUME_STEPS;

		// 0) 界面自己的权威值先更新（音量条读它，不再反查混音器缓存）
		this.currentVolume = volume;

		// 1) 本地立即生效（只作用于本方块，不影响其它收音机）
		ClientRadioPlayback.setBlockVolume(volume);
		if (this.blockPos != null) {
			radiosmile.client.audio.PcmSink.INSTANCE.setVolumeFor(this.blockPos.asLong(), volume);
		}

		// 2) 同步到服务端（节流）
		this.syncVolumeThrottled(volume);
	}

	/** 音量同步节流：拖动时每秒最多 10 次，松手时强制补发最终值。 */
	private void syncVolumeThrottled(float volume) {
		if (this.blockPos == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - this.lastVolumeSyncAt < VOLUME_SYNC_INTERVAL_MS) {
			this.pendingVolumeSync = true;
			return;
		}
		this.lastVolumeSyncAt = now;
		this.pendingVolumeSync = false;
		ClientRadioNetwork.setVolume(this.blockPos, volume);
	}

	/** 把尚未发出的最终音量立刻同步出去。 */
	private void flushVolumeSync() {
		if (this.blockPos == null || !this.pendingVolumeSync) {
			return;
		}
		this.pendingVolumeSync = false;
		this.lastVolumeSyncAt = System.currentTimeMillis();
		ClientRadioNetwork.setVolume(this.blockPos, ClientRadioPlayback.blockVolume());
	}

	/**
	 * 鼠标滚轮：在列表区域内滚动并切换选中项。
	 *
	 * <p>把滚轮同时用作「选择」是刻意的——收音机需要快速换台，
	 * 单纯滚动列表还得再点一下，多一步操作。
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (mouseX < this.listLeft || mouseX > this.listRight
				|| mouseY < this.listTop || mouseY > this.listBottom) {
			return false;
		}
		if (this.shown.isEmpty()) {
			return false;
		}
		this.moveSelection(delta > 0 ? -1 : 1);
		return true;
	}

	private int clampScroll(int value) {
		int content = this.shown.size() * ROW_STEP;
		int view = this.listBottom - this.listTop;
		int max = Math.max(0, content - view);
		return Math.max(0, Math.min(value, max));
	}

	@Override
	public void onClose() {
		// 关闭界面时兜底再同步一次音量，避免有未发出的变更
		if (this.blockPos != null) {
			this.pendingVolumeSync = true;
			this.flushVolumeSync();
		}
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

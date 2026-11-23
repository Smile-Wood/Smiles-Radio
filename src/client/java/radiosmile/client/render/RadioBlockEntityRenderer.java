package radiosmile.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import radiosmile.block.RadioBlock;
import radiosmile.block.entity.RadioBlockEntity;
import radiosmile.net.AudioStatus;

/**
 * 收音机方块的状态显示层：<b>故障时</b>红灯快闪，并在液晶屏上写出 {@code NOSIGNAL}。
 *
 * <h2>为什么用 BlockEntityRenderer 而不是改方块模型</h2>
 * 需求是「故障时<b>无视亮度</b>」。1.20.1 的原版方块模型没有自发光（emissive）支持，
 * 要让模型某部分无视光照必须 mixin 改动 lightmap 计算，侵入性大、易与其他模组冲突。
 *
 * <p>改成额外叠一层 BER：正常播放时<b>根本不绘制</b>（方法开头直接 return），
 * 只有故障时才画。于是原有方块模型/贴图一行都不用改。
 *
 * <h2>液晶屏<b>不重绘底色</b>（用户要求）</h2>
 * 早期版本故障时会把液晶屏区域刷成暗红底（{@code LCD_BG_FAULT}），
 * 用户明确否定：<b>显示屏背景保持贴图原样</b>，只有<b>文字变黑</b>。
 * 这也更符合真实设备——液晶屏的偏光片底色是物理固定的，变的只有笔画。
 * 因此这里<b>只画红灯 + 黑字</b>，液晶屏区域一个四边形都不画。
 *
 * <h2>几何体来源与三个定位推导（每一个都踩过坑）</h2>
 * <ul>
 *   <li><b>正面平面</b>：模型主箱体 {@code from [3,0,7] to [13,6,9]}，
 *       正面是 {@code north} 面、uv {@code [0,0,5,3]}，
 *       位于 {@code z = from[2] = 7 = 7/16}、朝 <b>-Z</b>。
 *       误用 {@code to[2]=9} 就会画到方块背面。</li>
 *   <li><b>朝向旋转</b>：{@code Axis.YP.rotationDegrees(180 - facing.toYRot())}。
 *       见 {@link #ROTATION_EXPLANATION}。</li>
 *   <li><b>贴图 u 方向</b>：正面 u=0 在<b>模型 +X</b> 侧，即贴图左侧对应高 x。
 *       见 {@link #pxToX(float)}。</li>
 * </ul>
 */
public class RadioBlockEntityRenderer implements BlockEntityRenderer<RadioBlockEntity> {
	/**
	 * 方块正面的 z 平面：{@code from[2] = 7}，不是 {@code to[2] = 9}。
	 */
	private static final float FRONT_Z = 7.0F / 16.0F;

	/**
	 * 让显示层略微浮在方块表面之上，避免 z-fighting。
	 *
	 * <p>取 0.0035 格（约 0.056 方块像素）：早期用 0.002，在远视角或深度精度不足的
	 * 设备上仍可能闪烁；0.0035 既能稳定压过表面，视觉上也看不出浮起。
	 */
	private static final float Z_EPSILON = 0.0035F;

	/**
	 * <b>为什么是 {@code 180 - toYRot()}，而不是 {@code -toYRot()}</b>
	 *
	 * <p>{@code Direction.toYRot()} 是「该朝向绕 Y 的<b>世界角</b>」：
	 * {@code north=180, east=270, south=0, west=90}。
	 * 而 blockstates 里的 {@code "y"} 是「<b>模型</b>绕 Y 的旋转角」。
	 * 两者差 180°——因为模型正面画在 {@code north}（朝 -Z）上，
	 * 而 {@code toYRot()} 描述的是「面朝方向」而不是「模型本地 -Z 转到哪」。
	 *
	 * <p>推导（以 facing=south 为例）：玩家在方块南侧看向北，方块正面朝南（+Z）。
	 * 模型本地 -Z 必须转到世界 +Z，即绕 Y 转 180°。
	 * {@code 180 - toYRot(south=0) = 180} ✓。
	 * 再验 east：本地 -Z 要转到世界 +X，需绕 Y 转 -90°；{@code 180 - 270 = -90} ✓。
	 * 而原式 {@code -toYRot(east) = -270 = +90}，转到反方向 180°。
	 *
	 * <p><b>后果（实测）</b>：整个 overlay 被画到方块<b>背面</b>，
	 * 且四边形的法线/绕序背对观察者，被背面剔除直接吞掉——
	 * 现象就是「红灯和液晶屏完全不显示」；而文字用 SEE_THROUGH 不参与剔除，
	 * 于是能穿过后脑勺被看到，且呈「上下颠倒 + 左右镜像」。
	 */
	private static final String ROTATION_EXPLANATION = "";

	/** 正面对应的贴图像素范围（与模型 uv {@code [0,0,5,3]} 一致）。 */
	private static final float FACE_PX_W = 80.0F;
	private static final float FACE_PX_H = 48.0F;

	/** 正面在方块内的 x 起止（模型 {@code x=3..13}）。 */
	private static final float FACE_X_LOW = 3.0F / 16.0F;
	private static final float FACE_X_HIGH = 13.0F / 16.0F;
	/** 正面在方块内的 y 起止（模型 {@code y=0..6}）。 */
	private static final float FACE_Y_LOW = 0.0F;
	private static final float FACE_Y_HIGH = 6.0F / 16.0F;

	/**
	 * 红灯（POWER 指示灯）在贴图中的像素范围。
	 *
	 * <p>实测贴图：{@code x=71..78, y=1..3}，即 7×2 像素，位于正面右上角。
	 */
	private static final float LED_PX_X0 = 71.0F;
	private static final float LED_PX_X1 = 78.0F;
	private static final float LED_PX_Y0 = 1.0F;
	private static final float LED_PX_Y1 = 3.0F;

	/**
	 * <b>液晶屏绿色显示区</b>在贴图中的像素范围（用于文字居中与宽度限制）。
	 *
	 * <p>实测贴图：绿区 {@code x=30..75, y=8..20}（46×13），
	 * 其外一圈黑色边框在 {@code x=28..77, y=6..22}。
	 * 这里刻意<b>只取绿区</b>而不是含边框的整块：黑色文字若压到黑色边框上就消失了，
	 * 所以宽度与居中都必须以绿区为准。
	 *
	 * <p><b>这个矩形只用来定位文字，不绘制任何底色</b>（见类注释）——
	 * 用户要求显示屏背景保持贴图原样，只有文字变黑。
	 */
	private static final float LCD_PX_X0 = 30.0F;
	private static final float LCD_PX_X1 = 75.0F;
	private static final float LCD_PX_Y0 = 8.0F;
	private static final float LCD_PX_Y1 = 20.0F;

	/** 故障时红灯的亮红色。 */
	private static final int LED_COLOR_FAULT = 0xFFFF2020;

	/**
	 * 故障时红灯<b>闪烁</b>的半周期（毫秒）。
	 *
	 * <p>红灯是「通路坏了」的告警灯，真实设备上就是一闪一闪的；
	 * 常亮反而像"电源正常"。
	 *
	 * <p>取 <b>250ms</b>（即 2Hz、每 0.5 秒一个明暗周期）——用户反馈「闪得快一点」，
	 * 这个速率既明显又不刺眼。再快（≤120ms）会和显示器刷新产生拍频，
	 * 看起来像"抖动"而不是"闪"。
	 *
	 * <p>用墙钟而不是 {@code level.getGameTime()}：后者受暂停影响，
	 * 单人游戏暂停时灯会"冻"在半亮状态，很怪。
	 */
	private static final long LED_BLINK_HALF_PERIOD_MS = 250L;

	/**
	 * 液晶屏上显示的故障文字。
	 *
	 * <p>用户要求把 {@code ERRnnn} 改成 <b>{@code NOSIGNAL}</b>：
	 * 玩家不需要看懂错误码，只需要知道「没信号」。
	 *
	 * <p><b>不闪烁、常显</b>——文字是给人读的，闪起来根本读不清；
	 * 需要"闪"的是红灯，那是余光可见的告警信号。两者职责不同。
	 */
	private static final String FAULT_TEXT = "NOSIGNAL";

	/**
	 * 液晶屏文字颜色：<b>黑色</b>。
	 *
	 * <p>用户明确要求「字要黑的」。真实设备上液晶的笔画是深色（透光/遮光），
	 * 背景的绿色是偏光片固有底色——两者是分开的，所以底色不该被覆盖。
	 */
	private static final int LCD_TEXT_COLOR = 0xFF000000;

	/**
	 * 纯色四边形要采样的<b>白色方块贴图</b>。
	 *
	 * <h2>为什么不能只给 uv(0,0)..(1,1)</h2>
	 * {@code RenderType.translucent()} 的纹理状态是 {@code BLOCK_SHEET_MIPPED}，
	 * 也就是<b>整张方块图集</b>（1024×1024 里塞着所有方块贴图）。
	 * {@code uv 0..1} 在 GL 里就是「整张贴图」——四边形会把<b>整张图集缩放着画出来</b>，
	 * 而不是纯色。顶点色只是<b>乘</b>在贴图上，无法把图案抹平成纯色。
	 *
	 * <p>正确做法：取图集里一个<b>均匀白色</b>的小 sprite，四边形只采样它的中心区域。
	 * 白色 × 顶点色 = 想要的纯色。
	 *
	 * <p>实测三个候选贴图的平均色（16×16，均 alpha=255）：
	 * {@code snow=(249,254,254)}、{@code white_wool=(234,236,237)}、
	 * {@code white_concrete=(207,213,214)}。
	 * 选 {@code snow} —— 它最接近纯白，乘上去的颜色偏差 <2%，肉眼无差；
	 * 且雪是方块必有贴图，不会因资源包缺失而变成品红/紫黑格。
	 */
	private static final ResourceLocation SOLID_WHITE_SPRITE =
			new ResourceLocation("minecraft", "block/snow");

	/** 缓存的白色 sprite（图集在资源重载后才会变，所以只缓存一次并做失效保护）。 */
	private TextureAtlasSprite whiteSprite;

	private final Font font;

	public RadioBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.getFont();
	}

	/**
	 * 取（并缓存）白色 sprite。
	 *
	 * <p>图集在资源包重载时会整个替换，缓存的 sprite 对象会过期（UV 指向旧图集位置）。
	 * 用 {@code Minecraft.getInstance().getTextureAtlas(...)} 每次重取代价很低（一次 map 查表），
	 * 这里就每次都取，不做缓存——避免"换资源包后 overlay 变色"这类难查问题。
	 *
	 * @return 找不到时返回 null（调用方应跳过绘制，而不是画出一坨图集内容）
	 */
	private TextureAtlasSprite resolveWhiteSprite() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return null;
		}
		try {
			this.whiteSprite = client.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
					.apply(SOLID_WHITE_SPRITE);
			return this.whiteSprite;
		} catch (Throwable t) {
			// 图集尚未就绪（极早期渲染）：本次不画，下一帧自然会好
			return null;
		}
	}

	@Override
	public void render(RadioBlockEntity be, float partialTick, PoseStack pose,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		int fault = be.getClientFaultStatus();
		BlockState state = be.getBlockState();
		Direction facing = state.hasProperty(RadioBlock.FACING)
				? state.getValue(RadioBlock.FACING)
				: Direction.NORTH;

		pose.pushPose();
		// 把坐标系旋转到方块正面朝向；模型是绕方块中心渲染的。
		//
		// <b>这里必须是 180 - toYRot()</b>，理由见 ROTATION_EXPLANATION 与类注释。
		// 写成 -toYRot() 会让整个 overlay 落到背面（红灯/液晶屏被背面剔除，完全不可见）。
		pose.translate(0.5, 0.0, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
		pose.translate(-0.5, 0.0, -0.5);

		Matrix4f matrix = pose.last().pose();
		// 正面朝 <b>-Z</b>：overlay 要稍微向观察者一侧（-Z）浮出，
		// 才能压住方块表面而不被它遮挡。
		float z = FRONT_Z - Z_EPSILON;

		// <b>正常播放时：液晶屏显示该频道的 rate（频率/档位）</b>（用户要求）。
		//
		// 早期 {@code fault == OK} 时整个渲染器直接 return，屏幕保持贴图自带的空白绿色——
		// 于是一台正在正常播放的收音机，液晶屏上是空的。现在把频道配置里的 {@code rate}
		// 写上去（如 {@code FM90.7}），让玩家一眼看到"在放哪个台"。
		//
		// 注意：<b>仍然不绘制液晶屏底色</b>，黑字写在贴图原本的绿屏上。
		// 正常时不画红灯（根本没有故障，不该亮告警灯）。
		if (fault == AudioStatus.OK) {
			String rate = currentRate(be);
			if (rate != null && !rate.isBlank()) {
				drawLcdText(pose, buffers, rate, z, packedLight, LCD_TEXT_COLOR);
			}
			pose.popPose();
			return;
		}

		// 1) 红灯（POWER 指示灯）：<b>最大亮度</b> + <b>快闪</b>。
		//
		// 真实设备的电源灯是自发光的，暗处也必须看得见——这是「设备是否通电」
		// 的唯一视觉信号，若跟着环境变暗就失去意义，所以用 FULL_BRIGHT。
		//
		// 故障时按 {@link #LED_BLINK_HALF_PERIOD_MS} 闪烁：灭相时<b>整个四边形都不画</b>
		// （而不是画成黑色）——方块本身的贴图上那里是暗红"未点亮"状态，
		// 不画就自然露出它，观感正是"灯灭了"。
		//
		// <b>液晶屏底色不绘制</b>：用户要求保持贴图原样，只有文字变黑。
		if (blinkOn()) {
			drawPixelQuad(matrix, buffers, z,
					LED_PX_X0, LED_PX_X1, LED_PX_Y0, LED_PX_Y1,
					LED_COLOR_FAULT, LightTexture.FULL_BRIGHT);
		}

		// 2) NOSIGNAL 文字：黑色，写在贴图自带的绿色液晶屏上。
		// <b>不跟随闪烁</b>：文字要能读，闪起来就废了（见 FAULT_TEXT 说明）。
		drawFaultText(pose, buffers, z, packedLight);

		pose.popPose();
	}

	/**
	 * 取该方块当前频道的 {@code rate}（屏幕要显示的内容）。
	 *
	 * <h2>数据来源与线程</h2>
	 * 方块实体只存 <b>频道 id</b>；显示文案来自服务端下发的频道列表
	 * （{@code RadioStation#displayRate()}，未配置时是 {@code NETWORK}）。
	 *
	 * <p>渲染在<b>渲染线程</b>执行，而 {@code ClientRadioData.stations()}
	 * 是一个 volatile 不可变列表引用（写入时整体替换，见 {@code acceptStations}），
	 * 因此这里读取是安全的、不需要额外同步。
	 *
	 * @return 要显示的文案；方块无频道或列表里还没有该频道时返回 {@code null}
	 */
	private static String currentRate(RadioBlockEntity be) {
		if (be == null) {
			return null;
		}
		String stationId = be.getStation();
		if (stationId == null || stationId.isBlank()) {
			// 没有选台：屏幕上什么都不写（保持贴图自带的空白绿屏）
			return null;
		}
		radiosmile.radio.RadioStation station =
				radiosmile.client.radio.ClientRadioData.findStation(stationId);
		if (station == null) {
			// 频道不在本地列表里（列表还没到 / 已失效）：不显示，
			// 这种情况的故障提示由 fault != OK 的分支负责（显示 NOSIGNAL）。
			return null;
		}
		return station.displayRate();
	}

	/**
	 * 当前是否是红灯的「亮」相。
	 *
	 * <p>用 {@link System#currentTimeMillis()} 而不是游戏时间：
	 * 游戏暂停（单人 ESC / 后台）时美术资源仍会渲染一帧，
	 * 用 {@code level.getGameTime()} 会让灯卡在某一个相位不动。
	 *
	 * <p>纯函数、无状态：同一帧内红灯与文字各调一次也不会看到不同结果
	 * （文字本来就不闪，这里只是说明为什么不做"计时器累加"）。
	 */
	private static boolean blinkOn() {
		return (System.currentTimeMillis() / LED_BLINK_HALF_PERIOD_MS) % 2L == 0L;
	}

	/**
	 * 贴图像素 X → 方块坐标（<b>已处理 u 轴的反向</b>）。
	 *
	 * <h2>为什么是 {@code FACE_X_HIGH - ...} 而不是 {@code FACE_X_LOW + ...}</h2>
	 * 原版 <b>north 面的 u 轴指向模型 -X</b>：贴图左边缘（u=0）出现在 <b>+X 侧</b>。
	 * 两条独立证据，结论一致：
	 * <ol>
	 *   <li><b>反编译原版 {@code net.minecraft.client.renderer.FaceInfo}</b>
	 *       （1.20.1，mojmap）里 NORTH 的四个顶点依次是
	 *       {@code (MAX_X,MAX_Y,MIN_Z)}, {@code (MAX_X,MIN_Y,MIN_Z)},
	 *       {@code (MIN_X,MIN_Y,MIN_Z)}, {@code (MIN_X,MAX_Y,MIN_Z)}；
	 *       再按 {@code BlockFaceUV#getU(int)} 的索引规则
	 *       （顶点 0/1 取 {@code uvs[0]}，顶点 2/3 取 {@code uvs[2]}），
	 *       得到 {@code MAX_X → u1}、{@code MIN_X → u2}。
	 *       也就是 <b>X 越小、u 越大</b>。</li>
	 *   <li><b>用户截图实测</b>：方块朝玩家（facing=south），观众看向 -Z，
	 *       此时世界 +X 在观众右手。截图里黑色面板在<b>左</b>、红灯在<b>右</b>；
	 *       而贴图中黑色面板在低 u（3..25）、红灯在高 u（71..78）。
	 *       于是「低 u = 屏幕左 = 观众左侧 = 模型 +X」，
	 *       同样得出「u=0 在高 x 侧」。</li>
	 * </ol>
	 *
	 * <p>旧实现写成 {@code FACE_X_LOW + ...}（低 u ↔ 低 x），漏了这次反向，
	 * 所以即使朝向修好，液晶屏与红灯仍会左右颠倒。
	 */
	private static float pxToX(float px) {
		return FACE_X_HIGH - (px / FACE_PX_W) * (FACE_X_HIGH - FACE_X_LOW);
	}

	/**
	 * 贴图像素 Y → 方块坐标（Y 轴向下，需翻转）。
	 *
	 * <p>{@code v=0}（贴图最上一行）对应方块<b>最高</b>处 {@code y=6/16}，
	 * 与 north 面 uv 方向一致，<b>不需要</b>像 X 那样反向。
	 */
	private static float pxToY(float px) {
		return FACE_Y_HIGH - (px / FACE_PX_H) * (FACE_Y_HIGH - FACE_Y_LOW);
	}

	/**
	 * 按<b>贴图像素矩形</b>画一个正对前方的四边形。
	 *
	 * <p>调用方只需给出贴图坐标，绕序与 y 轴翻转在这里统一处理，
	 * 避免每个调用点各自算一遍上下界（历史上把 yTop/yBottom 传反导致内容上下颠倒）。
	 *
	 * <h2>⚠️ 为什么必须在这里做 min/max 归一化（不是防御性编程，是硬约束）</h2>
	 * {@link #pxToX(float)} 是<b>镜像</b>映射（u=0 在 +X 侧），所以
	 * {@code pxToX(pxX0) > pxToX(pxX1)} 是常态（例如红灯：pxToX(71)=0.2578 > pxToX(78)=0.2031）。
	 *
	 * <p>而 {@link #drawQuad} 的顶点序 {@code (x0,yB),(x0,yT),(x1,yT),(x1,yB)}
	 * 只有在 <b>x0 < x1</b> 时叉积才给出 <b>-Z</b>（朝观察者）。
	 * 若把镜像后的原始值直接传下去（x0 > x1），同一顶点序的几何法线会翻成 <b>+Z</b>，
	 * 被 {@code RenderType.translucent()} 的 CULL 状态剔除 —— <b>四边形仍然完全不可见</b>。
	 *
	 * <p>这两种"看不见"的现象完全一样，极难区分：一个是朝向旋转错（改 `180-toYRot` 解决），
	 * 一个是镜像后绕序翻转（本方法解决）。核对时务必两者都验：
	 * <b>镜像 pxToX 与顶点序必须成对处理。</b>
	 */
	private void drawPixelQuad(Matrix4f matrix, MultiBufferSource buffers, float z,
			float pxX0, float pxX1, float pxY0, float pxY1, int color, int light) {
		TextureAtlasSprite sprite = this.resolveWhiteSprite();
		if (sprite == null) {
			// 图集没就绪：宁可不画，也不要拿 uv 0..1 把整张图集摊在方块上
			return;
		}
		float xa = pxToX(pxX0);
		float xb = pxToX(pxX1);
		float ya = pxToY(pxY0);
		float yb = pxToY(pxY1);
		// 统一成「左下 xMin / 右上 xMax / 上 yTop / 下 yBottom」，不依赖调用方传参顺序
		drawQuad(matrix, buffers, z,
				Math.min(xa, xb), Math.max(ya, yb),
				Math.max(xa, xb), Math.min(ya, yb), color, light, sprite);
	}

	/**
	 * 画一个朝向前方的四边形。
	 *
	 * @param light 顶点光照：{@link LightTexture#FULL_BRIGHT} 表示无视环境亮度，
	 *              传 {@code packedLight} 则跟随方块实际光照
	 */
	private void drawQuad(Matrix4f matrix, MultiBufferSource buffers, float z,
			float x0, float yTop, float x1, float yBottom, int color, int light,
			TextureAtlasSprite sprite) {
		float a = ((color >> 24) & 0xFF) / 255.0F;
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >> 8) & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;

		// 用半透明面渲染类型：它带 alpha 混合、不写深度，适合这种贴在方块表面的 overlay。
		//
		// <b>必须给 normal</b>：{@code RenderType.translucent()} 的顶点格式是
		// {@code POSITION_COLOR_TEX_LIGHTMAP_NORMAL}，少写一个元素就会抛
		// {@code IllegalStateException: Not filled all elements of the vertex}。
		// 这个错误只在【真正开始渲染】时才暴露——而这个渲染器平时完全不画，
		// 所以直到断网触发故障显示才第一次崩出来。
		VertexConsumer vc = buffers.getBuffer(RenderType.translucent());
		// 正面朝 <b>-Z</b>（方块 north 面），因此：
		//   - 法线取 (0, 0, -1)
		//   - 顶点绕序从 -Z 侧看是逆时针：(x0,yBottom) → (x0,yTop) → (x1,yTop) → (x1,yBottom)
		//     叉积 e1×e2 = (0,h,0)×(w,h,0) = (0,0,-h·w)，正好是 -Z ✓
		//
		// 早期法线写 (0,0,1) 且按 +Z 绕序，结果这个面背对观察者、
		// 被背面剔除直接吞掉——现象就是「指示灯完全不亮」。
		//
		// <b>UV 必须落在白色 sprite 的<b>中心</b></b>，而<b>不是</b> 0..1：
		// 后者在 GL 里表示"整张贴图"，而这里的贴图是<b>整张方块图集</b>
		// （BLOCK_SHEET_MIPPED），于是四边形会把图集缩放铺满 —— 不是纯色。
		// 取 sprite 中心的一小块（16% 见方）既能避开边界像素的插值混色，又足够取样均匀。
		float u0 = sprite.getU0();
		float u1 = sprite.getU1();
		float v0 = sprite.getV0();
		float v1 = sprite.getV1();
		float uInset = (u1 - u0) * 0.42F;
		float vInset = (v1 - v0) * 0.42F;
		float ua = u0 + uInset;
		float ub = u1 - uInset;
		float va = v0 + vInset;
		float vb = v1 - vInset;

		vc.vertex(matrix, x0, yBottom, z).color(r, g, b, a)
				.uv(ua, vb).uv2(light)
				.normal(0.0F, 0.0F, -1.0F).endVertex();
		vc.vertex(matrix, x0, yTop, z).color(r, g, b, a)
				.uv(ua, va).uv2(light)
				.normal(0.0F, 0.0F, -1.0F).endVertex();
		vc.vertex(matrix, x1, yTop, z).color(r, g, b, a)
				.uv(ub, va).uv2(light)
				.normal(0.0F, 0.0F, -1.0F).endVertex();
		vc.vertex(matrix, x1, yBottom, z).color(r, g, b, a)
				.uv(ub, vb).uv2(light)
				.normal(0.0F, 0.0F, -1.0F).endVertex();
	}

	/**
	 * 在液晶屏区域写出 <b>{@code NOSIGNAL}</b>（黑色，常显不闪）。
	 *
	 * <h2>文字变换为什么是 {@code scale(s, -s, s)} 再绕 Y 转 180°</h2>
	 * 字体空间约定：{@code +x} = 阅读方向（向右），{@code +y} = <b>向下</b>，
	 * {@code +z} = 朝向读者。{@code drawInBatch} 的 {@code y} 参数是字形<b>顶部</b>。
	 *
	 * <p>要把它摆到朝 -Z 的方块正面上、且对站在 -Z 侧的读者「正着读」，需要：
	 * <ul>
	 *   <li>{@code ẑ → -Z}：字面朝观察者 —— 由绕 Y 转 180° 完成；</li>
	 *   <li>{@code ŷ → -Y}：字体「向下」要变成方块「向下」。
	 *       少了这个负号，字就是<b>上下颠倒</b>的（实测现象之一）；</li>
	 *   <li>{@code x̂ → -X}：阅读方向。facing=north 时观众站在 -Z 看向 +Z，
	 *       其屏幕右方是 -X，所以文字必须沿 -X 前进才读得通。</li>
	 * </ul>
	 * 三者合起来正是 {@code M = Ry(180°) · diag(s, -s, s)}。
	 * 注意 {@code diag(s,-s,s)} 的行列式为负——这是<b>必然</b>的：
	 * 字形是一张固定朝向的平面，要让它从背面看仍然不镜像，只能引入一次镜射。
	 * 原版告示牌正面文字用的也是 {@code scale(s, -s, s)}（见 1.20.1 {@code SignRenderer#translateSignText}），
	 * 是同一套道理。
	 *
	 * <p>旧实现只有 {@code scale(s, s, s)}、缺了 {@code -s}，
	 * 于是文字上下颠倒；再叠加当时错误的朝向旋转，最终呈「镜像 + 倒置」。
	 */
	private void drawFaultText(PoseStack pose, MultiBufferSource buffers,
			float z, int packedLight) {
		// <b>固定文案 NOSIGNAL</b>，不再按状态码拼 {@code ERRnnn}。
		//
		// 早期屏幕上是 ERR403/404/408 这类设备式故障码，出发点（真实设备观感）没错，
		// 但用户实测后明确要求：玩家只需要知道「没信号」，不需要懂错误码。
		// 具体原因仍可在日志里查到，屏幕上保持最简。
		drawLcdText(pose, buffers, FAULT_TEXT, z, packedLight, LCD_TEXT_COLOR);
	}

	/**
	 * 在液晶屏绿区里写一行字（水平垂直居中，按实际宽度自适应缩放）。
	 *
	 * <p>抽成通用方法，让「故障时的 NOSIGNAL」与「正常时的 rate」共用同一套
	 * 定位/缩放/变换逻辑——历史教训：这两类文字曾经各写一份，结果一份修好了、
	 * 另一份还留着同样的镜像/倒置 bug。
	 *
	 * @param text  要写的文字（空/null 直接返回）
	 * @param color 文字颜色（{@code 0xAARRGGBB}）
	 */
	private void drawLcdText(PoseStack pose, MultiBufferSource buffers, String text,
			float z, int packedLight, int color) {
		if (this.font == null || text == null || text.isBlank()) {
			return;
		}
		// 基准字号。
		//
		// 关键约束：屏上文案最长的是 {@code NOSIGNAL}（8 字符）。
		// 按原版默认字体的字形宽度逐字累加（含 1px 字距）实测为 <b>50px</b>：
		// N/O/S/G/A 各 6、L 5、I 2，共 43px + 7×1px 字距。
		// 液晶屏绿区换算到方块约 0.3516 格，留 0.02 边距后可用 0.3316 格，
		// 故基准字号取 <b>0.0065</b>（50 × 0.0065 = 0.325 ≤ 0.3316，恰好铺满且不溢出）。
		//
		// 不能用 0.009（那是原来 ERR408 的值）：50 × 0.009 = 0.45 格，
		// 会超出屏幕 28%，被下方回缩逻辑硬压下去，字反而变小且不稳定。
		//
		// 较短的文案（如 {@code FM90.7}）若也用同一字号会显得偏小，
		// 所以下面按宽度做了"放大到刚好铺满"的处理（见 fitScale），
		// 但设了上限，避免两三个字符被放大得比屏幕还高。
		float maxWidthBlocks = Math.abs(pxToX(LCD_PX_X0) - pxToX(LCD_PX_X1)) - 0.02F;
		int textWidth = this.font.width(text);

		// <b>按宽度自适应</b>：先按"刚好铺满绿区"求出理想字号，
		// 再用上下限夹住。这样短文案（FM90.7）不会缩成蚂蚁，
		// 长文案（NOSIGNAL）也不会溢出。
		float scale = 0.0065F;
		if (textWidth > 0) {
			scale = maxWidthBlocks / textWidth;
		}
		// 下限：保持 0.0065（再小就糊成一团，8 字符的 NOSIGNAL 会落在这一档）
		scale = Math.max(0.0065F, scale);
		// 上限：不能超过 0.013，否则 2~3 个字符会撑满整个屏幕高度、像广告牌。
		// 同时受"屏幕高度"约束——lineHeight(9) 乘 scale 不能超过绿区高度。
		float maxByHeight = (Math.abs(pxToY(LCD_PX_Y0) - pxToY(LCD_PX_Y1)) * 0.8F)
				/ Math.max(1, this.font.lineHeight);
		scale = Math.min(scale, Math.max(0.0065F, Math.min(0.013F, maxByHeight)));

		// 在液晶屏（含边框矩形）内水平、垂直居中。
		// 宽度按文字自身实际宽度算，避免依赖固定字符数。
		float centerX = (pxToX(LCD_PX_X0) + pxToX(LCD_PX_X1)) * 0.5F;
		float centerY = (pxToY(LCD_PX_Y0) + pxToY(LCD_PX_Y1)) * 0.5F;

		pose.pushPose();
		// 略微再往前一点，压过红灯四边形，避免同深度闪烁
		pose.translate(centerX, centerY, z - 0.0005F);
		// <b>y 取负</b>：字体 +y 向下，方块 +Y 向上（见方法注释，少了它会上下颠倒）
		pose.scale(scale, -scale, scale);
		// 字面从默认的 +Z 转到 -Z，面向观察者
		pose.mulPose(Axis.YP.rotationDegrees(180.0F));

		// 水平居中：从 -半宽 开始画
		float halfWidth = this.font.width(text) * 0.5F;
		// 垂直居中：drawInBatch 的 y 是字形顶部，且此时 y 已翻转，
		// 所以上移半行高即可让字形跨在 centerY 两侧
		float topY = -this.font.lineHeight / 2.0F;

		// POLYGON_OFFSET：世界空间文字的正确显示模式（原版告示牌亦用它）。
		// 它参与深度测试（会被前方的方块正常遮挡），但用深度偏移压制与
		// 承载面同深度的 z-fighting。早期用 SEE_THROUGH（不测深度）会
		// 让文字穿过整块方块显示，正是「背面文字也能看见」的原因之一。
		this.font.drawInBatch(text, -halfWidth, topY, color,
				false, pose.last().pose(), buffers,
				Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);

		pose.popPose();
	}

	/**
	 * 是否需要每帧渲染。
	 *
	 * <h2>现在<b>始终返回 true</b></h2>
	 * 早期这里 {@code return fault != OK}，理由是"正常播放时这个渲染器不出图，省掉开销"。
	 * 但本轮起<b>正常播放也要画</b>——液晶屏上要显示该频道的 {@code rate}（频率/档位）。
	 * 若仍在这里剔除，正常播放时就永远看不到频率。
	 *
	 * <p>开销很小：一次 {@code getClientFaultStatus()}（volatile 读）+ 一张小字条，
	 * 且方块本身有视锥/LOD 剔除兜底。
	 */
	@Override
	public boolean shouldRender(RadioBlockEntity be, Vec3 cameraPos) {
		return true;
	}

	/** 本渲染器的实际绘制范围就是方块本身，不需要按「离屏也绘制」处理。 */
	@Override
	public boolean shouldRenderOffScreen(RadioBlockEntity be) {
		return false;
	}
}

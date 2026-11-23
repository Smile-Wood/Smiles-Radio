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
 * 收音机方块的状态显示层：<b>故障时</b>点亮红灯并在液晶屏上写出错误码。
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
		if (fault == AudioStatus.OK) {
			// 正常播放：完全不额外绘制，方块保持原有光照外观
			return;
		}

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

		// 1) 红灯（POWER 指示灯）：<b>最大亮度</b>。
		//
		// 真实设备的电源灯是自发光的，暗处也必须看得见——这是「设备是否通电」
		// 的唯一视觉信号，若跟着环境变暗就失去意义，所以用 FULL_BRIGHT。
		//
		// <b>液晶屏底色不绘制</b>：用户要求保持贴图原样，只有文字变黑。
		drawPixelQuad(matrix, buffers, z,
				LED_PX_X0, LED_PX_X1, LED_PX_Y0, LED_PX_Y1,
				LED_COLOR_FAULT, LightTexture.FULL_BRIGHT);

		// 2) 错误码文字：黑色，写在贴图自带的绿色液晶屏上。
		drawFaultText(pose, buffers, be, fault, z, packedLight);

		pose.popPose();
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
	 * 在液晶屏区域写出错误码（<b>黑色</b>）。
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
	private void drawFaultText(PoseStack pose, MultiBufferSource buffers, RadioBlockEntity be,
			int fault, float z, int packedLight) {
		if (this.font == null) {
			return;
		}
		String text = AudioStatus.errorCode(fault);
		if (text == null || text.isBlank()) {
			return;
		}
		// 基准字号。
		//
		// 液晶屏绿区换算到方块约 0.35 × 0.09 格；{@code ERR408} 在默认字体下约 36px 宽，
		// 因此 0.009 左右正好铺满（36 × 0.009 = 0.324 < 0.35）。
		// 下方另有按实际宽度回缩的兜底，防止不同字体/语言溢出。
		float scale = 0.009F;
		float maxWidthBlocks = Math.abs(pxToX(LCD_PX_X0) - pxToX(LCD_PX_X1)) - 0.02F;
		float maxWidthPx = maxWidthBlocks / scale;

		int textWidth = this.font.width(text);
		if (textWidth > maxWidthPx && textWidth > 0) {
			scale *= maxWidthPx / textWidth;
		}

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
		this.font.drawInBatch(text, -halfWidth, topY, LCD_TEXT_COLOR,
				false, pose.last().pose(), buffers,
				Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);

		pose.popPose();
	}

	/**
	 * 正常播放时直接跳过渲染，省掉每帧调用开销；
	 * 故障时按常规距离剔除即可。
	 */
	@Override
	public boolean shouldRender(RadioBlockEntity be, Vec3 cameraPos) {
		return be.getClientFaultStatus() != AudioStatus.OK;
	}

	/** 本渲染器的实际绘制范围就是方块本身，不需要按「离屏也绘制」处理。 */
	@Override
	public boolean shouldRenderOffScreen(RadioBlockEntity be) {
		return false;
	}
}

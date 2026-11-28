package radiosmile.client.integration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

import radiosmile.SmileSRadio;
import radiosmile.client.config.RadioConfigScreenFactory;

/**
 * Mod Menu 配置入口。
 *
 * <p>用动态代理而不是匿名类：{@code ConfigScreenFactory#create(Screen)} 会把 {@code Screen}
 * 写进常量池，而该项目启用了 split source sets，直接实现会引入不必要的耦合。
 * 代理只按接口方法名分派，全程不触碰具体界面类型。
 *
 * <p>本类只在装了 Mod Menu 时才会被加载（Loader 的 entrypoint 惰性解析），
 * 因此 Mod Menu 写在 {@code fabric.mod.json} 的 {@code suggests} 里而非 {@code depends}。
 *
 * <p><b>前置库缺失时返回空工厂：</b>{@code smile_libbox} 只用于渲染配置界面，
 * 服务端完全不碰它，因此它写在 {@code recommends} 而不是 {@code depends}。
 * 缺库时这里返回 {@code null}（Mod Menu 视为没有配置屏），
 * 而不是让 {@link RadioConfigScreenFactory} 触发 {@code NoClassDefFoundError}。
 */
public final class ModMenuIntegration implements ModMenuApi {

	/** 前置库是否在场。类加载是惰性的，这里只查 mod id，不会触碰它的类。 */
	private static final boolean LIBBOX_PRESENT = FabricLoader.getInstance().isModLoaded("smile_libbox");

	private static final ConfigScreenFactory<?> FACTORY = createFactory();

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FACTORY;
	}

	private static ConfigScreenFactory<?> createFactory() {
		return (ConfigScreenFactory<?>) Proxy.newProxyInstance(
				ConfigScreenFactory.class.getClassLoader(),
				new Class<?>[]{ConfigScreenFactory.class},
				new ScreenFactory());
	}

	private static final class ScreenFactory implements InvocationHandler {
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "create":
					if (!LIBBOX_PRESENT) {
						SmileSRadio.LOGGER.warn("未安装 Smile's Lib Box，配置界面不可用；模组其余功能不受影响");
						return null;
					}
					Object parent = args != null && args.length > 0 ? args[0] : null;
					try {
						return RadioConfigScreenFactory.create(parent);
					} catch (NoClassDefFoundError | NoSuchMethodError e) {
						// 库在场但版本过旧、缺类缺方法时的兜底：宁可没有配置屏，也不让点按钮崩游戏
						SmileSRadio.LOGGER.error("Smile's Lib Box 版本不兼容，配置界面无法打开", e);
						return null;
					}
				case "equals":
					return proxy == args[0];
				case "hashCode":
					return System.identityHashCode(proxy);
				case "toString":
					return "RadioSmileConfigScreenFactory";
				default:
					throw new UnsupportedOperationException(method.toString());
			}
		}
	}
}

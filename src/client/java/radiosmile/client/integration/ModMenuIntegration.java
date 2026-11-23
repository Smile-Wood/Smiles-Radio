package radiosmile.client.integration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

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
 */
public final class ModMenuIntegration implements ModMenuApi {

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
					Object parent = args != null && args.length > 0 ? args[0] : null;
					return RadioConfigScreenFactory.create(parent);
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

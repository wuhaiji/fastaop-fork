package org.fastlight.aop.processor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

import com.google.common.collect.Sets;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import org.fastlight.aop.annotation.FastAspect;
import org.fastlight.aop.annotation.FastNone;
import org.fastlight.aop.translator.FastAspectTranslator;
import org.fastlight.apt.model.compile.MethodCompile;
import org.fastlight.apt.processor.BaseFastProcessor;
import org.fastlight.apt.util.FastCollections;

/**
 * @author ychost@outlook.com
 * @date 2021-03-27
 */
public class FastAspectProcessor extends BaseFastProcessor<FastNone> {

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Sets.newHashSet("*");
    }
    
    /**
     * 随机取一个支持植入代码的注解，因为一个方法可能有多个注解支持切入
     */
    AnnotationMirror getSupportAtm(Element element) {
        List<? extends AnnotationMirror> atms = element.getAnnotationMirrors();
        if (FastCollections.isEmpty(atms)) {
            return null;
        }
        Set<String> supportTypes = AspectSupportTypes.getSupportTypes();
        for (AnnotationMirror atm : atms) {
            List<? extends AnnotationMirror> metaAtms = atm.getAnnotationType().asElement().getAnnotationMirrors();
            
            // 如果这个注解是提前在配置spi文件中配置的就表示这个元素会被 fastaop 支持切入
            if (supportTypes.contains(atm.getAnnotationType().toString())) {
                return atm;
            }
            
            // 或者如果这个注解上的元注解包含@FastAspect表示这个元素会被 fastaop 支持切入
            for (AnnotationMirror metaAtm : metaAtms) {
                if (supportTypes.contains(metaAtm.getAnnotationType().toString())) {
                    // 加入受支持的注解类型列表中。
                    // 这里不太优雅，这里强依赖 FastAspectProcessor 先于 FastAspectVarProcessor 执行，
                    // 否则先执行 FastAspectVarProcessor会出现异常
                    // 好处是可以不用写spi文件（META-INF/aspect/fast.aspect.supports.txt）了
                    supportTypes.add(atm.getAnnotationType().toString());
                    return atm;
                }
            }
        }
        return null;
    }

    @Override
    public void processExecutableElement(ExecutableElement executableElement, AnnotationMirror atm) {
        Symbol.ClassSymbol ownerElement = getOwnerElement(executableElement, Symbol.ClassSymbol.class);
        JCMethodDecl jcMethodDecl = javacTrees.getTree(executableElement);
        if (jcMethodDecl == null || ownerElement == null) {
            return;
        }
        // 优先取方法上面的注解
        atm = getSupportAtm(executableElement);
        if (atm == null) {
            atm = getSupportAtm(ownerElement);
        }
        if (atm == null) {
            return;
        }
        // 不切构造函数和初始化
        if (!Optional.ofNullable(jcMethodDecl.getReturnType()).map(v -> v.type).isPresent()) {
            return;
        }
        // 不切匿名类
        if (!Optional.ofNullable(jcMethodDecl.sym).map(v -> v.owner).map(v -> v.type).isPresent()) {
            return;
        }
        MethodCompile methodCompile = new MethodCompile();
        methodCompile.setMethodDecl(jcMethodDecl);
        methodCompile.setOwnerElement(ownerElement);
        // 仅有 FastAspect 可指定 builder
        if (FastAspect.class.getName().equals(atm.getAnnotationType().toString())) {
            methodCompile.addExtension("builder", getAtValueData(atm, "builder"));
        }
        methodCompile.setMethodElement(executableElement);
        FastAspectTranslator translator = getTranslator(methodCompile);
        // 防止重复织入
        if (translator.isMarkedMethod()) {
            return;
        }
        JCClassDecl ownerClass = javacTrees.getTree(ownerElement);
        // 1. 添加类元数据缓存
        translator.addMetaOwnerVar(ownerClass);
        // 2. 添加方法元数据缓存
        translator.addMetaMethodVar(ownerClass);
        // 3. 方法内部织入切面代码
        translator.weaveMethod();
        // 4. 处理 return 和 局部变量
        jcMethodDecl.accept(translator);
    }

    /**
     * 获取语法树遍历器
     *
     * @param methodCompile 注入上下文变量
     */
    protected FastAspectTranslator getTranslator(MethodCompile methodCompile) {
        FastAspectTranslator translator = new FastAspectTranslator(treeMaker, names.table, messager);
        translator.init(methodCompile);
        return translator;
    }

    @Override
    public void processTypeElement(TypeElement typeElement, AnnotationMirror atm) {
        processExecutableOfTypeElement(typeElement, atm, true);
    }
}

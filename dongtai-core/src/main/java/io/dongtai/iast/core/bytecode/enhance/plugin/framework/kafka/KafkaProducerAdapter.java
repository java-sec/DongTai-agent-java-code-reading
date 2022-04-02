package io.dongtai.iast.core.bytecode.enhance.plugin.framework.kafka;

import io.dongtai.iast.core.bytecode.enhance.IastContext;
import io.dongtai.iast.core.bytecode.enhance.plugin.AbstractClassVisitor;
import io.dongtai.log.DongTaiLog;
import org.objectweb.asm.*;

public class KafkaProducerAdapter extends AbstractClassVisitor {
    public KafkaProducerAdapter(ClassVisitor classVisitor, IastContext context) {
        super(classVisitor, context);
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        int argCount = Type.getArgumentTypes(desc).length;

        if ("send".equals(name) && argCount == 2) {
            if (DongTaiLog.isDebugEnabled()) {
                DongTaiLog.debug("Adding kafka tracking for type {}", context.getClassName());
            }

            mv = new KafkaProducerSendAdviceAdapter(mv, access, name, desc);
            setTransformed();
        }
        return mv;
    }
}

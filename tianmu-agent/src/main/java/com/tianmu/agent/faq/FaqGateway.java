package com.tianmu.agent.faq;

import com.tianmu.agent.preparation.FaqPort;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Optional;

/** Bundled capabilities take priority. Learned answers are private and threshold-gated. */
@Component
@Primary
public final class FaqGateway implements FaqPort {
    private final ClasspathFaqPort bundled;private final QaCatalog learned;
    public FaqGateway(ObjectProvider<ClasspathFaqPort> bundled,QaCatalog learned){this.bundled=bundled.getIfAvailable();this.learned=learned;}
    public Optional<FaqAnswer> find(Input input,CallControl control){
        control.checkpoint();
        Optional<FaqAnswer> fixed=bundled==null?Optional.empty():bundled.find(input,control);
        if(fixed.isPresent())return fixed;control.checkpoint();
        try{return learned.find(input);}
        catch(com.tianmu.agent.core.AgentException e){
            if("QA_UNAVAILABLE".equals(e.code()))throw new IllegalStateException("Q&A lookup unavailable",e);
            throw e;
        }
    }
}

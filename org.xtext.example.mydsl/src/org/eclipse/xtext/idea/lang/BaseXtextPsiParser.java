package org.eclipse.xtext.idea.lang;

import static org.eclipse.xtext.psi.PsiEObject.XTEXT_EREFERENCE_KEY;
import static org.eclipse.xtext.psi.PsiEObject.XTEXT_NODE_KEY;
import static org.eclipse.xtext.psi.PsiReferenceEObject.XTEXT_ECONTEXT_KEY;
import static org.eclipse.xtext.psi.PsiReferenceEObject.XTEXT_EPROXY_KEY;

import java.util.Iterator;
import java.util.Map;

import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.xtext.AbstractElement;
import org.eclipse.xtext.Assignment;
import org.eclipse.xtext.CrossReference;
import org.eclipse.xtext.GrammarUtil;
import org.eclipse.xtext.idea.lang.CreateElementType.CreateCallback;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.SyntaxErrorMessage;
import org.eclipse.xtext.psi.impl.BaseXtextFile;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.SimpleAttributeResolver;

import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiBuilder.Marker;
import com.intellij.lang.PsiParser;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.tree.IElementType;

public class BaseXtextPsiParser implements PsiParser {
	
	@Inject
	private IElementTypeProvider elementTypeProvider;
	
	protected static class CreateWithNodeAndReferenceCallback implements CreateCallback {

		private final INode node;
		private final EReference reference;
		
		private final EObject proxy;
		private final EObject context;
		
		private final EObject semanticElement;
		
		public CreateWithNodeAndReferenceCallback(INode node) {
			this(node, null, null, null, null);
		}
		
		public CreateWithNodeAndReferenceCallback(INode node, EReference reference, EObject semanticElement) {
			this(node, reference, null, null, semanticElement);
		}

		public CreateWithNodeAndReferenceCallback(INode node, EReference reference, EObject proxy, EObject context, EObject semanticElement) {
			this.node = node;
			this.reference = reference;
			
			this.proxy = proxy;
			this.context = context;
			
			this.semanticElement = semanticElement;
		}

		public void onCreate(CompositeElement composite) {
			composite.putUserData(XTEXT_NODE_KEY, node);
			composite.putUserData(XTEXT_EREFERENCE_KEY, reference);
			
			composite.putUserData(XTEXT_EPROXY_KEY, proxy);
			composite.putUserData(XTEXT_ECONTEXT_KEY, context);
			
			if (semanticElement != null) {
				Iterator<Adapter> adapters = semanticElement.eAdapters().iterator();
				while (adapters.hasNext()) {
					Adapter adapter = adapters.next();
					if (adapter instanceof PsiAdapter) {
						adapters.remove();
					}
				}
				semanticElement.eAdapters().add(new PsiAdapter(composite));
			}
		}
		
	}
	
	public static class PsiAdapter extends AdapterImpl {

		private final CompositeElement composite;

		public PsiAdapter(CompositeElement composite) {
			this.composite = composite;
		}
		
		public CompositeElement getComposite() {
			return composite;
		}
		
		public static CompositeElement getComposite(EObject object) {
			if (object == null) {
				return null;
			}
			Iterator<Adapter> adapters = object.eAdapters().iterator();
			while (adapters.hasNext()) {
				Adapter adapter = adapters.next();
				if (adapter instanceof PsiAdapter) {
					return ((PsiAdapter) adapter).getComposite();
				}
			}
			return null;
		}
		
	}
	
	public ASTNode parse(IElementType rootType, PsiBuilder psiBuilder) {
		psiBuilder.setDebugMode(true);
		
		BaseXtextFile containingFile = getContainingFile(psiBuilder);
		Resource resource = containingFile.createResource();
		ICompositeNode rootNode = ((XtextResource) resource).getParseResult().getRootNode();
		
		PsiBuilder.Marker rootMarker = psiBuilder.mark();
		parse(rootNode, new ParsingContext(psiBuilder, containingFile));
		while(!psiBuilder.eof()) {
			psiBuilder.advanceLexer();
		}
		rootMarker.done(rootType);
		
		return psiBuilder.getTreeBuilt();
	}
	
	protected void parse(INode node, ParsingContext context) {
		if (node instanceof ILeafNode) {
			parse((ILeafNode) node, context);
		} else if (node instanceof ICompositeNode) {
			parse((ICompositeNode) node, context);
		} else {
			throw new IllegalStateException("Unknown node: " + node);
		}
	}
	
	@SuppressWarnings("unchecked")
	protected void parse(ILeafNode node, ParsingContext context) {
        if (node.isHidden()) {
        	return;
        }
        CreateCallback createCallback = null;
        IElementType elementType = null;
        EObject grammarElement = node.getGrammarElement();
        if (grammarElement instanceof CrossReference) {
        	EReference reference = GrammarUtil.getReference((CrossReference) grammarElement);
			elementType = elementTypeProvider.getCrossReferenceType();
			Object value = context.semanticElement.eGet(reference, false);
			if (reference.isMany()) {
				Integer index = context.getIndex(reference);
				value = ((InternalEList<EObject>) value).get(index);
			}
			EObject proxy = (EObject) value;
			createCallback = new CreateWithNodeAndReferenceCallback(node, reference, proxy, context.semanticElement, null);
        } else if (grammarElement instanceof AbstractElement && context.semanticElement != null) {
        	EAttribute attribute = SimpleAttributeResolver.NAME_RESOLVER.getAttribute(context.semanticElement);
    		if (attribute != null) {
    			EClass referenceOwner = context.semanticElement.eClass();
        		Assignment assignment = GrammarUtil.containingAssignment(grammarElement);
        		if (assignment != null) {
					String feature = assignment.getFeature();
	            	EStructuralFeature structuralFeature = ((EClass) referenceOwner).getEStructuralFeature(feature);
	            	if (attribute == structuralFeature) {
	                	elementType = elementTypeProvider.getNameType();
	                	context.namedElement = true;
	        			createCallback = new CreateWithNodeAndReferenceCallback(node);
	            	}
        		}
    		}
        }
        Marker nodeMarker = null;
        if (elementType != null) {
        	nodeMarker = context.builder.mark();
        }
        Marker errorMarker = null;
        SyntaxErrorMessage syntaxErrorMessage = node.getSyntaxErrorMessage();
        if (syntaxErrorMessage != null) {
        	errorMarker = context.builder.mark();
        }
        context.builder.advanceLexer();
        if (errorMarker != null) {
        	errorMarker.error(syntaxErrorMessage.getMessage());
        }
		if (nodeMarker != null) {
        	nodeMarker.done(new CreateElementType(elementType, createCallback));
		}
	}
	
	protected void parse(ICompositeNode node, ParsingContext context) {
		Marker errorMarker = null;
        SyntaxErrorMessage syntaxErrorMessage = node.getSyntaxErrorMessage();
        if (syntaxErrorMessage != null) {
        	errorMarker = context.builder.mark();
        }
		Marker elementMarker = null;
		if (node.hasDirectSemanticElement()) {
			elementMarker = context.builder.mark();
			context = context.newContext(node.getSemanticElement());
		}
		for (INode child : node.getChildren()) {
			parse(child, context);
		}
		if (elementMarker != null) {
			EObject semanticElement = node.getSemanticElement();
			final EReference reference = semanticElement.eContainmentFeature();
			
			CreateWithNodeAndReferenceCallback createCallback = new CreateWithNodeAndReferenceCallback(node, reference, null, null, context.semanticElement);
			if (context.namedElement) {
				elementMarker.done(new CreateElementType(elementTypeProvider.getNamedObjectType(), createCallback));
			} else {
				elementMarker.done(new CreateElementType(elementTypeProvider.getObjectType(), createCallback));
			}
		}
		if (errorMarker != null) {
        	errorMarker.error(syntaxErrorMessage.getMessage());
		}
	}

	protected BaseXtextFile getContainingFile(PsiBuilder builder) {
		return (BaseXtextFile) builder.getUserDataUnprotected(FileContextUtil.CONTAINING_FILE_KEY);
	}
	
	protected static class ParsingContext {
		
		public boolean namedElement = false;
		public EObject semanticElement;
		public Map<EReference, Integer> referenceToIndex;

		public final PsiBuilder builder;
		public final BaseXtextFile file;

		public ParsingContext(PsiBuilder builder, BaseXtextFile file) {
			this.file = file;
			this.builder = builder;
		}

		public Integer getIndex(EReference reference) {
			Integer index = referenceToIndex.get(reference);
            if (index == null) {
                index = 0;
            }
            referenceToIndex.put(reference, index + 1);
            return index;
		}

		public ParsingContext newContext(EObject semanticElement) {
			ParsingContext context = new ParsingContext(builder, file);
			context.semanticElement = semanticElement;
			context.referenceToIndex = Maps.newHashMap();
			return context;
		}
		
	}

}


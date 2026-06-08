package com.facebook.presto.execution.planadapter;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;

import java.util.Map;

public class AdapterContext
{
    private final Session session;
    private final Metadata metadata;
    private final PlanNodeIdAllocator idAllocator;
    private final VariableAllocator variableAllocator;
    private final Map<String, String> queryToPlanFile;
    private final FunctionAndTypeManager functionAndTypeManager;
    private final ClassLoader classLoader;

    public AdapterContext(Session session, Metadata metadata, PlanNodeIdAllocator idAllocator, VariableAllocator variableAllocator, Map<String, String> queryToPlanFile, FunctionAndTypeManager functionAndTypeManager, ClassLoader classLoader)
    {
        this.session = session;
        this.metadata = metadata;
        this.idAllocator = idAllocator;
        this.variableAllocator = variableAllocator;
        this.queryToPlanFile = queryToPlanFile;
        this.functionAndTypeManager = functionAndTypeManager;
        this.classLoader = classLoader;
    }

    public Session getSession()
    {
        return session;
    }

    public Metadata getMetadata()
    {
        return metadata;
    }

    public PlanNodeIdAllocator getIdAllocator()
    {
        return idAllocator;
    }

    public VariableAllocator getVariableAllocator()
    {
        return variableAllocator;
    }

    public String getPlanFileForQuery(String queryId)
    {
        return queryToPlanFile.get(queryId);
    }

    public FunctionAndTypeManager getFunctionAndTypeManager()
    {
        return functionAndTypeManager;
    }

    public ClassLoader getClassLoader()
    {
        return classLoader;
    }
}

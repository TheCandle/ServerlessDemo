package com.facebook.presto.execution.planadapter;

import com.facebook.presto.common.CatalogSchemaName;
import com.facebook.presto.common.type.TypeSignature;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.FunctionKind;

import java.util.Collections;
import java.util.List;

public class PassthroughFunctionHandle
        implements FunctionHandle
{
    private final String name;

    public PassthroughFunctionHandle(String name)
    {
        this.name = name;
    }

    @Override
    public CatalogSchemaName getCatalogSchemaName()
    {
        return new CatalogSchemaName("presto", "default");
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public FunctionKind getKind()
    {
        return FunctionKind.SCALAR;
    }

    @Override
    public List<TypeSignature> getArgumentTypes()
    {
        return Collections.emptyList();
    }
}

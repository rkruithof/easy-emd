package nl.knaw.dans.easy.domain.model.emd;

import nl.knaw.dans.easy.domain.model.emd.types.ApplicationSpecific.MetadataFormat;

public final class EasyMetadataFactory
{
    private EasyMetadataFactory()
    {
        // never instantiate.
    }

    public static Class<? extends EasyMetadata> getEasyMetadataClass()
    {
        return EasyMetadataImpl.class;
    }

    public static EasyMetadata newEasyMetadata(MetadataFormat metadataFormat)
    {
        EasyMetadataImpl emd = new EasyMetadataImpl(metadataFormat);
        emd.setDirty(false);
        return emd;
    }

}

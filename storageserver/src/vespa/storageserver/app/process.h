// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class storage::Process
 *
 * \brief Storage process as a library.
 *
 * A class with a main function cannot be tested within C++ code. This class
 * contains the process as a library such that it can be tested and used in
 * other pieces of code.
 *
 * Specializations of this class will exist to add the funcionality needed for
 * the various process types.
 */

#pragma once

#include <vespa/config-bucketspaces.h>
#include <vespa/config-stor-distribution.h>
#include <vespa/config/subscription/configsubscriber.h>
#include <vespa/config/subscription/configuri.h>
#include <vespa/document/config/config-documenttypes.h>
#include <vespa/storage/config/config-stor-bouncer.h>
#include <vespa/storage/config/config-stor-communicationmanager.h>
#include <vespa/storage/config/config-stor-server.h>
#include <vespa/storage/storageserver/applicationgenerationfetcher.h>

namespace document { class DocumentTypeRepo; }

namespace storage {

class StorageNode;
struct StorageNodeContext;

class Process : public ApplicationGenerationFetcher {
protected:
    using DocumentTypesConfig        = document::config::DocumenttypesConfig;
    using BucketspacesConfig         = vespa::config::content::core::BucketspacesConfig;
    using CommunicationManagerConfig = vespa::config::content::core::StorCommunicationmanagerConfig;
    using StorBouncerConfig          = vespa::config::content::core::StorBouncerConfig;
    using StorDistributionConfig     = vespa::config::content::StorDistributionConfig;
    using StorServerConfig           = vespa::config::content::core::StorServerConfig;

    using DocumentTypeRepoSP = std::shared_ptr<const document::DocumentTypeRepo>;
    config::ConfigUri _configUri;
    DocumentTypeRepoSP getTypeRepo() { return _repos.back(); }
    config::ConfigSubscriber _configSubscriber;

    std::unique_ptr<config::ConfigHandle<DocumentTypesConfig>>        _document_cfg_handle;
    std::unique_ptr<config::ConfigHandle<BucketspacesConfig>>         _bucket_spaces_cfg_handle;
    std::unique_ptr<config::ConfigHandle<CommunicationManagerConfig>> _comm_mgr_cfg_handle;
    std::unique_ptr<config::ConfigHandle<StorBouncerConfig>>          _bouncer_cfg_handle;
    std::unique_ptr<config::ConfigHandle<StorDistributionConfig>>     _distribution_cfg_handle;
    std::unique_ptr<config::ConfigHandle<StorServerConfig>>           _server_cfg_handle;

private:
    std::vector<DocumentTypeRepoSP> _repos;

public:
    using UP = std::unique_ptr<Process>;

    explicit Process(const config::ConfigUri & configUri);
    ~Process() override;

    virtual void setupConfig(vespalib::duration subscribeTimeout);
    virtual void createNode() = 0;
    virtual bool configUpdated();
    virtual void updateConfig();

    virtual void shutdown();
    virtual void removeConfigSubscriptions() {}

    virtual StorageNode& getNode() = 0;
    virtual StorageNodeContext& getContext() = 0;

    int64_t getGeneration() const override;
};

} // storage


// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "garbagecollectionoperation.h"
#include <vespa/storage/distributor/idealstatemanager.h>
#include <vespa/storage/distributor/distributor.h>
#include <vespa/storage/distributor/distributor_bucket_space.h>
#include <vespa/storageapi/message/removelocation.h>

#include <vespa/log/log.h>
LOG_SETUP(".distributor.operation.idealstate.remove");

using namespace storage::distributor;

GarbageCollectionOperation::GarbageCollectionOperation(const std::string& clusterName, const BucketAndNodes& nodes)
    : IdealStateOperation(nodes),
      _tracker(clusterName)
{}

GarbageCollectionOperation::~GarbageCollectionOperation() = default;

void
GarbageCollectionOperation::onStart(DistributorMessageSender& sender)
{
    BucketDatabase::Entry entry = _bucketSpace->getBucketDatabase().get(getBucketId());
    std::vector<uint16_t> nodes = entry->getNodes();

    for (auto node : nodes) {
        auto command = std::make_shared<api::RemoveLocationCommand>(
                _manager->getDistributorComponent().getDistributor().getConfig().getGarbageCollectionSelection(),
                getBucket());

        command->setPriority(_priority);
        _tracker.queueCommand(command, node);
    }

    _tracker.flushQueue(sender);

    if (_tracker.finished()) {
        done();
    }
}

void
GarbageCollectionOperation::onReceive(DistributorMessageSender&,
                           const std::shared_ptr<api::StorageReply>& reply)
{
    auto* rep = dynamic_cast<api::RemoveLocationReply*>(reply.get());
    assert(rep != nullptr);

    uint16_t node = _tracker.handleReply(*rep);

    if (!rep->getResult().failed()) {
        _replica_info.emplace_back(_manager->getDistributorComponent().getUniqueTimestamp(),
                                   node, rep->getBucketInfo());
    } else {
        _ok = false;
    }

    if (_tracker.finished()) {
        if (_ok) {
            merge_received_bucket_info_into_db();
        }
        done();
    }
}

void GarbageCollectionOperation::merge_received_bucket_info_into_db() {
    // TODO avoid two separate DB ops for this. Current API currently does not make this elegant.
    _manager->getDistributorComponent().updateBucketDatabase(getBucket(), _replica_info);
    BucketDatabase::Entry dbentry = _bucketSpace->getBucketDatabase().get(getBucketId());
    if (dbentry.valid()) {
        dbentry->setLastGarbageCollectionTime(
                _manager->getDistributorComponent().getClock().getTimeInSeconds().getTime());
        _bucketSpace->getBucketDatabase().update(dbentry);
    }
}

bool
GarbageCollectionOperation::shouldBlockThisOperation(uint32_t, uint8_t) const
{
    return true;
}

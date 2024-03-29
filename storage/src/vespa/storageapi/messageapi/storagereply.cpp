// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "storagereply.h"
#include "storagecommand.h"
#include <ostream>

namespace storage::api {

StorageReply::StorageReply(const StorageCommand& cmd)
    : StorageReply(cmd, ReturnCode())
{}

StorageReply::StorageReply(const StorageCommand& cmd, ReturnCode code)
    : StorageMessage(cmd.getType().getReplyType(), cmd.getMsgId(), cmd.originator_msg_id()),
      _result(std::move(code))
{
    setPriority(cmd.getPriority());
    if (cmd.getAddress()) {
        setAddress(*cmd.getAddress());
    }
    // TODD do we really need copy construction
    if ( ! cmd.getTrace().isEmpty()) {
        setTrace(vespalib::Trace(cmd.getTrace()));
    }  else {
        getTrace().setLevel(cmd.getTrace().getLevel());
    }
    setTransportContext(cmd.getTransportContext());
}

StorageReply::~StorageReply() = default;

void
StorageReply::print(std::ostream& out, bool , const std::string& ) const
{
    out << "StorageReply(" << getType().getName() << ", " << _result << ")";
}

}

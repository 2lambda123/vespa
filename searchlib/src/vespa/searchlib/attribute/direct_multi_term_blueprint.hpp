// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "direct_multi_term_blueprint.h"
#include "document_weight_or_filter_search.h"
#include <vespa/searchlib/fef/termfieldmatchdata.h>
#include <vespa/searchlib/queryeval/emptysearch.h>
#include <memory>

namespace search::attribute {

template <typename SearchType>
DirectMultiTermBlueprint<SearchType>::DirectMultiTermBlueprint(const queryeval::FieldSpec &field,
                                                               const IAttributeVector &iattr,
                                                               const IDocidWithWeightPostingStore &attr,
                                                               size_t size_hint)
    : ComplexLeafBlueprint(field),
      _weights(),
      _terms(),
      _iattr(iattr),
      _attr(attr),
      _dictionary_snapshot(_attr.get_dictionary_snapshot())
{
    set_allow_termwise_eval(true);
    _weights.reserve(size_hint);
    _terms.reserve(size_hint);
}

template <typename SearchType>
DirectMultiTermBlueprint<SearchType>::~DirectMultiTermBlueprint() = default;

template <typename SearchType>
std::unique_ptr<queryeval::SearchIterator>
DirectMultiTermBlueprint<SearchType>::createLeafSearch(const fef::TermFieldMatchDataArray &tfmda, bool) const
{
    assert(tfmda.size() == 1);
    assert(getState().numFields() == 1);
    if (_terms.empty()) {
        return std::make_unique<queryeval::EmptySearch>();
    }
    std::vector<DocidWithWeightIterator> iterators;
    const size_t numChildren = _terms.size();
    iterators.reserve(numChildren);
    for (const IDirectPostingStore::LookupResult &r : _terms) {
        _attr.create(r.posting_idx, iterators);
    }
    bool field_is_filter = getState().fields()[0].isFilter();
    if (field_is_filter && tfmda[0]->isNotNeeded()) {
        return attribute::DocumentWeightOrFilterSearch::create(std::move(iterators));
    }
    return SearchType::create(*tfmda[0], field_is_filter, _weights, std::move(iterators));
}

template <typename SearchType>
std::unique_ptr<queryeval::SearchIterator>
DirectMultiTermBlueprint<SearchType>::createFilterSearch(bool, FilterConstraint) const
{
    std::vector<DocidWithWeightIterator> iterators;
    iterators.reserve(_terms.size());
    for (const IDirectPostingStore::LookupResult &r : _terms) {
        _attr.create(r.posting_idx, iterators);
    }
    return attribute::DocumentWeightOrFilterSearch::create(std::move(iterators));
}

}

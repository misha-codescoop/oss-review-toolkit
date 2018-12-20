/*
 * Copyright (c) 2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

import React from 'react';
import {
    List, Modal, Tabs, Tag, Tooltip
} from 'antd';
import PropTypes from 'prop-types';
import LicenseSummaryCard from './LicenseSummaryCard';
import { LICENSES } from '../data/licenses';
import 'antd/dist/antd.css';

const { TabPane } = Tabs;

export default class LicenseTag extends React.Component {
    constructor(props) {
        super(props);

        this.tagText = props.text;
        this.ellipsisAtChar = props.ellipsisAtChar;

        if (this.tagText) {
            this.license = LICENSES[this.tagText];

            this.showLicenseInfoModal = (e) => {
                e.stopPropagation();

                if (!this.license) {
                    return;
                }

                if (this.license.name !== 'NONE') {
                    if (!this.license.modal) {
                        this.license.modal = {
                            title: this.license.name,
                            className: 'ort-license-info',
                            content: (<LicenseInfo license={this.license} />),
                            onOk() {},
                            okText: 'Close',
                            maskClosable: true,
                            width: 800
                        };
                    }

                    Modal.info(this.license.modal);
                }
            };
        }
    }

    render() {
        if (this.tagText) {
            return (
                <Tooltip
                    placement="left"
                    title={this.license
                        ? this.license.name : this.tagText}
                >
                    <Tag
                        className="ort-license"
                        color={this.license ? this.license.color : ''}
                        checked
                        onClick={this.license && this.showLicenseInfoModal}
                    >
                        {(this.ellipsisAtChar
                            && this.tagText.length >= this.ellipsisAtChar)
                            ? `${this.tagText.substr(0, this.ellipsisAtChar)}...` : this.tagText}
                    </Tag>
                </Tooltip>);
        }
        return (
            <div>
                No data
            </div>
        );
    }
}

LicenseTag.propTypes = {
    ellipsisAtChar: PropTypes.number,
    text: PropTypes.string.isRequired
};

LicenseTag.defaultProps = {
    ellipsisAtChar: 0
};

// Generates the HTML for the additional license information
const LicenseInfo = (props) => {
    const { license } = props;
    const licenseDescription = license.description
        ? license.description : 'No description available for this license';

    if (!license && !license.summary) {
        return (
            <div>
                No summary data for this license
            </div>
        );
    }

    // Transform array of license summaries by provider so
    // we can display and attribute each provider's license summary
    const summaryProviders = ((summary = license.summary) => {
        const providers = {};

        for (let i = 0; i < summary.length; i += 1) {
            const { provider } = summary[i];

            if (provider) {
                if (!providers[provider]) {
                    providers[provider] = [];
                }

                providers[provider].push(summary[i]);
            }
        }

        return Object.values(providers);
    })();

    return (
        <div className="ort-license-info">
            <Tabs>
                <TabPane tab="Summary" key="1">
                    <p className="ort-license-description">
                        {licenseDescription}
                    </p>
                    <div className="ort-license-obligations">
                        <List
                            grid={{ gutter: 16, column: 1 }}
                            itemLayout="vertical"
                            size="small"
                            pagination={{
                                hideOnSinglePage: true,
                                pageSize: 1,
                                size: 'small'
                            }}
                            dataSource={summaryProviders}
                            renderItem={summary => (
                                <List.Item>
                                    <LicenseSummaryCard summary={summary} />
                                </List.Item>
                            )}
                        />
                    </div>
                </TabPane>
            </Tabs>
        </div>
    );
};

LicenseInfo.propTypes = {
    license: PropTypes.object.isRequired
};

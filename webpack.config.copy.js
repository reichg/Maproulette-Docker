// if using webpack move to maproulette3 root
// const path = require('path');

module.exports = {
    module: {
        rules: [
            {
                test: /\.(js|jsx)$/,
                exclude: /node_modules/,
                use: {
                    loader: "babel-loader",
                    options: {
                        presets: [
                            "@babel/preset-env",
                            "@babel/preset-react", {
                                'plugins': ['@babel/plugin-proposal-class-properties']
                            }
                        ]
                    }
                },


            },
            {
                test: /\.css$/,
                use: {
                    loader: 'css-loader',
                    options: {
                        modules: true
                    }
                }
            },
            {
                test: /\.(scss|sass)$/,
                use: {
                    loader: 'sass-loader',
                    options: {
                        modules: true
                    }

                }
            }
        ]
    },

    output: {
        path: __dirname + 'dist',
        filename: 'bundle.js'
    },

    watchOptions: {
        aggregateTimeout: 600,
        ignored: ['node_modules/**']
    }
}

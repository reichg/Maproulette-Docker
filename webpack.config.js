// if using webpack move to maproulette3 root
const path = require('path');
const precss = require('precss');
const autoprefixer = require('autoprefixer');

module.exports = {
    entry: path.resolve(__dirname, 'src/index.js'),
    watch: true,
    output: {
        path: __dirname,
        filename: 'main.js'
    },

    resolve: {
        extensions: ['.js', '.jsx']
    },

    devtool: 'eval-source-map',

    module: {
        rules: [
            {
                test: /\.jsx?$/,
                exclude: /node_modules/,
                use: [
                    {
                        loader: "babel-loader",
                        options: {
                            cacheDirectory: true,
                            presets: [
                                "@babel/preset-react",
                                "@babel/preset-env"
                            ],
                            plugins: [
                                '@babel/plugin-proposal-class-properties',
                                'babel-plugin-transform-class-properties'
                            ]
                        }
                    },
                ]
            },
            {
                enforce: 'pre',
                test: /\.js%/,
                loader: 'source-map-loader',
            },
            {
                test: /\.css$/,
                exclude: /node_modules/,
                use: [
                    {
                    loader : 'css-loader'}
                ]

            },
            {
                test: /\.s[ac]ss$/,
                exclude: /node_modules/,
                use: [
                    {
                        loader: 'style-loader',
                    }, 
                    {
                        loader: 'css-loader',
                    },
                    {
                        loader: 'postcss-loader', // run post CSS actions
                        options: {
                            plugins() {
                                return [
                                    precss,
                                    autoprefixer,
                                ];
                            }
                        }
                    },
                    {   
                        loader: 'sass-loader', // Compiles Sass to CSS
                        options: {
                        sourceMap: true,
                        }
                    },
                    {
                        loader: 'sass-resources-loader',
                        options: {
                            resources: [
                                path.resolve('./src/variables.scss'),
                                path.resolve('./src/mixins.scss'),
                                path.resolve('./src/theme.scss'),

                            ]
                        }    
                    }

                ]
            }
        ]
    },

    externals: {
        // eslint-disable-next-line quote-props
        'react': 'React',
        'react-dom': 'ReactDOM',
    },

    watchOptions: {
        aggregateTimeout: 1000,
        poll: 1000,
        ignored: /node_modules/
    }
}
